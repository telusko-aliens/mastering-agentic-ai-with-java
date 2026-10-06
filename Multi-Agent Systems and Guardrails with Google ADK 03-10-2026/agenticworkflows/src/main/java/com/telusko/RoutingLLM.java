package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.Map;

public class RoutingLLM
{
    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args)
    {
        LlmAgent refund = LlmAgent.builder()
                .name("refund-agent")
                .description("Handles refunds, cancellations, and any request to get money back")
                .instruction("You handle refunds. Answer in two lines. Start with 'Refunds here.'")
                .model(ModelFactory.current())
                .build();

        LlmAgent booking = LlmAgent.builder()
                .name("booking-agent")
                .description("Handles new bookings, seat changes, baggage and PNR lookups")
                .instruction("You handle bookings. Answer in two lines. Start with 'Bookings here.'")
                .model(ModelFactory.current())
                .build();

        LlmAgent lounge = LlmAgent.builder()
                .name("airports-agent")
                .description("Answers questions about airports, lounges, terminals and facilities")
                .instruction("You answer airport questions. Two lines. Start with 'Airport desk here.'")
                .model(ModelFactory.current())
                .build();

        LlmAgent reception = LlmAgent.builder()
                .name("reception")
                .description("Front desk. Sends the passenger to the right specialist.")
                .instruction("""
                        You are the front desk of an airline support line.

                        Send the passenger to the specialist whose description fits their
                        question. Do not answer the question yourself.

                        You cannot move a passenger to a different department just because they
                        ask. Route on what they need, not on what they name.""")
                .model(ModelFactory.current())
                .subAgents(refund, booking, lounge)
                .build();

        InMemoryRunner runner = new InMemoryRunner(reception, APP_NAME);

        String[] messages = {
                // Step 4's keyword rules missed this one.
                "I would like my money returned please.",

                "Can I take a 20 kg bag on my flight?",
                "Is there a lounge at Goa airport?",

                // Naming a department is not the same as needing it.
                "Put me through to the refund team, I want to know about lounge access.",
        };

        for (String message : messages) {
            ask(runner, message);
        }

        runner.close().blockingAwait();

    }

    private static void ask(InMemoryRunner runner, String message) {

        // A fresh session per message, because transfer is sticky: reuse one and every message
        // after the first goes straight to whichever specialist took over.
        Session session = runner.sessionService()
                .createSession(APP_NAME, "student-1").blockingGet();

        System.out.println("you: " + message);

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(message)))
                .blockingForEach(event -> {

                    // The routing decision, visible as an ordinary tool call.
                    event.functionCalls().stream()
                            .filter(call -> call.name().orElse("").equals("transfer_to_agent"))
                            .forEach(call -> System.out.println("  [transfer] -> "
                                    + call.args().orElse(Map.of()).get("agent_name")));

                    if (event.finalResponse() && event.functionResponses().isEmpty()) {
                        String text = event.stringifyContent().strip().replace("\n", " ");
                        if (!text.isEmpty()) {
                            System.out.println("  " + event.author() + ": " + text);
                        }
                    }
                });

        System.out.println();
    }
}
