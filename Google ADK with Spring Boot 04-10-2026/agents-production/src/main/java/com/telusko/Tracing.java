package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.Annotations.*;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.telusko.util.Tracer;

import java.util.Map;

public class Tracing
{
    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args) {
        Tracer tracer = new Tracer();


        LlmAgent fareAgent = traced(tracer, LlmAgent.builder()
                .name("fare-agent")
                .description("Quotes the fare for a route")
                .instruction("Use lookupFare for the price. Reply with one line.")
                .model(ModelFactory.current())
                .tools(FunctionTool.create(Tracing.class, "lookupFare")));

        LlmAgent baggageAgent = traced(tracer, LlmAgent.builder()
                .name("baggage-agent")
                .description("Explains the baggage allowance")
                .instruction("Cabin 7 kg, checked 15 kg domestic. Reply with one line.")
                .model(ModelFactory.current()));

        LlmAgent coordinator = traced(tracer, LlmAgent.builder()
                .name("coordinator")
                .description("Answers trip questions using specialists")
                .instruction("""
                        Use the fare-agent and baggage-agent tools as needed.
                        Write the final answer yourself, in two lines.""")
                .model(ModelFactory.current())
                .tools(
                        AgentTool.create(fareAgent),
                        AgentTool.create(baggageAgent)));

        InMemoryRunner runner = new InMemoryRunner(coordinator, APP_NAME);
        Session session = runner.sessionService()
                .createSession(APP_NAME, "student-1").blockingGet();

String question= "Wha does Pune to Goa Cost, "+
        "and how much baggage can I bring";


        System.out.println("you: " + question + "\n");

        runner.runAsync(
                        session.userId(),
                        session.id(),

                        Content.fromParts(
                                Part.fromText(question)
                        )
                )

                .blockingForEach(event -> {

                    if (event.finalResponse()
                            && event.functionCalls().isEmpty()
                            && event.functionResponses().isEmpty()) {

                        String text =
                                event.stringifyContent()
                                        .strip()
                                        .replace("\n", " ");


                        if (!text.isEmpty()) {

                            System.out.println(
                                    "\n  answer: " + text
                            );
                        }
                    }
                });


    }

    private static LlmAgent traced(Tracer tracer, LlmAgent.Builder builder) {
        return builder
                // Agent lifecyle
                .beforeAgentCallback(tracer.beforeAgent())
                .afterAgentCallback(tracer.afterAgent())

                // model lifecycle
                .beforeModelCallback(tracer.beforeModel())
                .afterModelCallback(tracer.afterModel())

                // tool lifecycle
                .beforeToolCallback(tracer.beforeTool())
                .afterToolCallback(tracer.afterTool())
                .build();
    }

    @Schema(
            description =
                    "Look up the fare in rupees for a route "
                            + "between two Indian cities."
    )
    public static Map<String, Object> lookupFare(

            @Schema(
                    name = "from",
                    description = "Origin city"
            )
            String from,

            @Schema(
                    name = "to",
                    description = "Destination city"
            )
            String to
    ) {

        try {

            Thread.sleep(600);

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();
        }


        return Map.of(
                "from", from,
                "to", to,
                "fare", 3450,
                "currency", "INR"
        );
    }

}
