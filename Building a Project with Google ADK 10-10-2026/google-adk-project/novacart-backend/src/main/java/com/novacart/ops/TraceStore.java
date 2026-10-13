package com.novacart.ops;

import com.google.adk.agents.Callbacks;
import io.reactivex.rxjava3.core.Maybe;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The six callbacks, kept as one bean so every agent shares it.
 *
 * <p>Class 4 printed the trace to the console. A running service needs the last few runs
 * queryable, so the staff screen can show what happened on a complaint somebody is arguing
 * about. Same callbacks, writing into a ring buffer instead of {@code System.out}.
 *
 * <p>Every callback returns {@code Maybe.empty()}, which means "carry on". A tracer must never
 * change behaviour; the moment one returns a value it has become a guardrail.
 */
@Component
public class TraceStore {

    /** One line of a trace. A record so it serialises to JSON without any help. */
    public record Line(long atMillis, int depth, String kind, String detail) {}

    /** One run, from the first agent starting to the last one finishing. */
    public record Run(String id, Instant startedAt, List<Line> lines,
                      int modelCalls, int toolCalls, long tokensIn, long tokensOut) {}

    /** How many runs to keep. Enough to debug the complaint in front of you, not a log store. */
    private static final int KEEP = 20;

    private final Deque<Run> finished = new ArrayDeque<>();

    /** Makes every stored run id unique. See the note in complete(). */
    private final AtomicLong stored = new AtomicLong();

    /**
     * In-flight runs, keyed by invocation id.
     *
     * <p>Keyed rather than a single field because a web application serves several passengers
     * at once. A tracer with one shared counter produces a trace that is the interleaving of
     * three conversations, which is worse than no trace at all.
     */
    private final Map<String, Builder> inFlight = new ConcurrentHashMap<>();

    private static class Builder {
        final String id;
        final Instant startedAt = Instant.now();
        final List<Line> lines = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final AtomicLong modelCalls = new AtomicLong();
        final AtomicLong toolCalls = new AtomicLong();
        final AtomicLong tokensIn = new AtomicLong();
        final AtomicLong tokensOut = new AtomicLong();
        int depth = 0;

        Builder(String id) {
            this.id = id;
        }

        void add(String kind, String detail) {
            lines.add(new Line(System.currentTimeMillis(), depth, kind, detail));
        }
    }

    private Builder forRun(String invocationId) {
        return inFlight.computeIfAbsent(invocationId, Builder::new);
    }

    // ------------------------------------------------------------------ agent

    public Callbacks.BeforeAgentCallback beforeAgent() {
        return context -> {
            Builder run = forRun(context.invocationId());
            run.add("agent-start", context.agentName());
            run.depth++;
            return Maybe.empty();
        };
    }

    public Callbacks.AfterAgentCallback afterAgent() {
        return context -> {
            Builder run = forRun(context.invocationId());
            run.depth = Math.max(0, run.depth - 1);
            run.add("agent-end", context.agentName());

            // The outermost agent finishing is the end of the run.
            if (run.depth == 0) {
                complete(context.invocationId());
            }

            return Maybe.empty();
        };
    }

    // ------------------------------------------------------------------ model

    public Callbacks.BeforeModelCallback beforeModel() {
        return (context, requestBuilder) -> {
            Builder run = forRun(context.invocationId());
            run.modelCalls.incrementAndGet();
            run.add("model", context.agentName());
            return Maybe.empty();
        };
    }

    public Callbacks.AfterModelCallback afterModel() {
        return (context, response) -> {

            Builder run = forRun(context.invocationId());

            response.usageMetadata().ifPresent(usage -> {
                int in = usage.promptTokenCount().orElse(0);
                int out = usage.candidatesTokenCount().orElse(0);
                run.tokensIn.addAndGet(in);
                run.tokensOut.addAndGet(out);
                run.add("tokens", in + " in / " + out + " out");
            });

            return Maybe.empty();
        };
    }

    // ------------------------------------------------------------------ tool

    public Callbacks.BeforeToolCallback beforeTool() {
        return (invocation, tool, args, toolContext) -> {
            Builder run = forRun(invocation.invocationId());
            run.toolCalls.incrementAndGet();
            run.add("tool", tool.name() + " " + args);
            return Maybe.empty();
        };
    }

    public Callbacks.AfterToolCallback afterTool() {
        return (invocation, tool, args, toolContext, result) -> {
            forRun(invocation.invocationId()).add("tool-done", tool.name());
            return Maybe.empty();
        };
    }

    // ------------------------------------------------------------------ store

    private void complete(String invocationId) {

        Builder run = inFlight.remove(invocationId);

        if (run == null) {
            return;
        }

        synchronized (finished) {
            // The stored id is NOT the invocation id, and that matters.
            //
            // An AgentTool runs its inner agent inside the caller's invocation, so depth comes
            // back to zero more than once under the same id and several finished runs end up
            // sharing it. A UI keying a list on that renders duplicates and React says so.
            //
            // The sequence keeps each stored run addressable; the invocation id is still the
            // first half of it when you need to correlate two runs from one turn.
            String storedId = run.id + "#" + stored.incrementAndGet();

            finished.addFirst(new Run(storedId, run.startedAt, List.copyOf(run.lines),
                    (int) run.modelCalls.get(), (int) run.toolCalls.get(),
                    run.tokensIn.get(), run.tokensOut.get()));

            while (finished.size() > KEEP) {
                finished.removeLast();
            }
        }
    }

    public List<Run> recent() {
        synchronized (finished) {
            return List.copyOf(finished);
        }
    }
}
