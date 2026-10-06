package com.telusko.util;

import com.google.adk.agents.Callbacks;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A trace of what a multi-agent run actually did.
 *
 * <p>Once there are four agents and six tools, "it gave the wrong answer" is not a bug report.
 * You need to know which agent was running, what it sent the model, which tool it called with
 * what arguments, how long each part took and what it cost.
 *
 * <p>ADK gives you all of that through callbacks. This class is nothing but those callbacks
 * wired to a printer and a few counters.
 *
 * <h2>Reading a trace</h2>
 *
 * <pre>
 *   -> agent   coordinator          an agent started
 *      model   coordinator          it called the model
 *      &lt;- 412 in / 38 out            and what that cost
 *      tool    fare-agent  {...}    it called a tool
 *      &lt;- 1204 ms                    and how long that took
 *   &lt;- agent   coordinator          the agent finished
 * </pre>
 *
 * <p>Indentation shows nesting, so a chain of agents calling agents reads top to bottom.
 */
public class Tracer {

    private final AtomicInteger depth = new AtomicInteger();

    //     * Number of LLM calls during the run.
    private final AtomicInteger modelCalls = new AtomicInteger();

    // number of tool calls
    private final AtomicInteger toolCalls = new AtomicInteger();

    //token counter
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();

    /** Start time per tool call id, so overlapping calls do not mix their timings up. */
    private final Map<String, Long> toolStarted = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ agent

    /**
     * Returning {@code Maybe.empty()} means "carry on normally".
     *
     * <p>That is the rule for every callback in ADK, and the whole of Step 5 rests on it:
     * return a value instead and you have replaced what was about to happen.
     */

    // rus before agent starts
    public Callbacks.BeforeAgentCallback beforeAgent() {
        return context -> {
            print(depth.getAndIncrement(), "-> agent  " + context.agentName());
            return Maybe.empty(); //continue nirmally
        };
    }
    // rus after agent finishes

    public Callbacks.AfterAgentCallback afterAgent() {
        return context -> {
            print(depth.decrementAndGet(), "<- agent  " + context.agentName());
            return Maybe.empty();
        };
    }

    // ------------------------------------------------------------------ model

    // runs immeditaly before every model call
    public Callbacks.BeforeModelCallback beforeModel() {
        return (context, requestBuilder) -> {
            modelCalls.incrementAndGet();
            print(depth.get(), "   model  " + context.agentName());
            return Maybe.empty();
        };
    }

    /**
     * Token counts, which is where the money is.
     *
     * <p>Input tokens are the ones people underestimate. Every turn resends the whole
     * conversation, so in a long chat the input dwarfs the output and the bill is mostly
     * history being re-read.
     */

    // runs immeditaly after every model call  --> best place to collect token usage

    public Callbacks.AfterModelCallback afterModel() {
        return (context, response) -> {

            response.usageMetadata().ifPresent(usage -> {
                int in = usage.promptTokenCount().orElse(0);
                int out = usage.candidatesTokenCount().orElse(0);
                inputTokens.addAndGet(in);
                outputTokens.addAndGet(out);
                print(depth.get(), "   <- " + in + " in / " + out + " out");
            });

            return Maybe.empty();
        };
    }

    // ------------------------------------------------------------------ tool

    // runs before a tool execution
    public Callbacks.BeforeToolCallback beforeTool() {
        return (invocation, tool, args, toolContext) -> {
            toolCalls.incrementAndGet();
            toolStarted.put(key(tool.name(), toolContext), System.currentTimeMillis());
            print(depth.get(), "   tool   " + tool.name() + "  " + args);
            return Maybe.empty();
        };
    }


    // runs after a tool execution

    public Callbacks.AfterToolCallback afterTool() {
        return (invocation, tool, args, toolContext, result) -> {

            Long started = toolStarted.remove(key(tool.name(), toolContext));
            if (started != null) {
                print(depth.get(), "   <- " + (System.currentTimeMillis() - started) + " ms");
            }

            return Maybe.empty();
        };
    }

    // ------------------------------------------------------------------ report

    public void summary() {
        System.out.println();
        System.out.println("  trace summary");
        System.out.println("    model calls : " + modelCalls.get());
        System.out.println("    tool calls  : " + toolCalls.get());
        System.out.println("    tokens in   : " + inputTokens.get());
        System.out.println("    tokens out  : " + outputTokens.get());
    }

    public void reset() {
        depth.set(0);
        modelCalls.set(0);
        toolCalls.set(0);
        inputTokens.set(0);
        outputTokens.set(0);
        toolStarted.clear();
    }

    // ------------------------------------------------------------------ helpers

    /** The function call id when there is one, so two calls to the same tool do not collide. */
    private static String key(String toolName, com.google.adk.tools.ToolContext context) {
        return toolName + "#" + context.functionCallId().orElse("");
    }

    private static void print(int level, String line) {
        System.out.println("  " + "   ".repeat(Math.max(0, level)) + line);
    }

    /** Unused here, kept because a trace is far more useful with the prompt in it. */
    static String preview(Content content) {
        return content.parts()
                .map(parts -> parts.isEmpty() ? "" : parts.get(0).text().orElse(""))
                .orElse("");
    }

    static String preview(LlmResponse response) {
        return response.content().map(Tracer::preview).orElse("");
    }
}
