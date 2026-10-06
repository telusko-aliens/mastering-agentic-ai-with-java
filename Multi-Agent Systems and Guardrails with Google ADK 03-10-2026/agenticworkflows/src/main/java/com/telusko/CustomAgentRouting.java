package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.telusko.util.TriageAgent;

public class CustomAgentRouting
{
    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args)
    {
        LlmAgent refund = specialist(
                "refund-agent",
                "Handles refunds and cancellations",
                "You handle refunds. Explain the refund process in two lines."
        );


        LlmAgent booking = specialist(
                "booking-agent",
                "Handles bookings, seats and PNR lookups",
                "You handle bookings and seats. Answer in two lines."
        );

        LlmAgent general = specialist(
                "general-agent",
                "Handles everything else",
                "You are a general airline assistant. Answer in two lines."
        );

        TriageAgent triage =
                new TriageAgent(
                        refund,
                        booking,
                        general
                );

        InMemoryRunner runner = new InMemoryRunner(triage, APP_NAME);

        String[] messages = {
                "I want a refund for my cancelled flight.",
                "Can I change my seat on TL1001?",
                "What time does the Goa airport lounge open?",

                // No keyword our rules know. It goes to the general agent, which is wrong.
                "I would like my money returned please.",
        };

        for (String message : messages) {
            ask(runner, message);
        }


        runner.close().blockingAwait();

    }

    private static void ask(InMemoryRunner runner, String message) {

        // A new session each time so the specialists start clean and the routing is the only
        // thing being demonstrated.
        Session session = runner.sessionService()
                .createSession(APP_NAME, "student-1").blockingGet();

        System.out.println("you: " + message);

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(message)))
                .blockingForEach(event -> {
                    if (event.finalResponse()) {
                        String text = event.stringifyContent().strip().replace("\n", " ");
                        if (!text.isEmpty()) {
                            System.out.println("  " + event.author() + ": " + text);
                        }
                    }
                });

        System.out.println();
    }
    private static LlmAgent specialist(String name, String description, String instruction) {
        return LlmAgent.builder()
                .name(name)
                .description(description)
                .instruction(instruction)
                .model(ModelFactory.current())
                .build();
    }
}
