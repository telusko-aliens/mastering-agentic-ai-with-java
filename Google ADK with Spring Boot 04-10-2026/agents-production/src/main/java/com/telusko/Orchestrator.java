package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.AgentTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.Map;

public class Orchestrator
{
    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args)
    {
        LlmAgent fareAgent = LlmAgent.builder()
                .name("fare-agent")
                .description("Quotes fares for a route")
                .instruction("""
                        You quote airline fares. Invent a plausible fare in rupees.
                        Reply with one line: the route and the fare.""")
                .model(ModelFactory.current())
                .build();

        LlmAgent weatherAgent = LlmAgent.builder()
                .name("weather-agent")
                .description("Describes typical weather at a destination")
                .instruction("""
                        You describe typical weather at a place and time of year.
                        Reply with one line.""")
                .model(ModelFactory.current())
                .build();

        //transfer pattern
        LlmAgent reception = LlmAgent.builder()
                .name("reception")
                .description("Front desk")
                .instruction("Send the passenger to the right specialist. Do not answer yourself.")
                .model(ModelFactory.current())
                .subAgents(fareAgent, weatherAgent)
                .build();

        System.out.println("########## Pattern A: transfer ##########");
        ask(reception, "What does Pune to Goa cost?");

        LlmAgent coordinator = LlmAgent.builder()
                .name("coordinator")
                .description("Plans a trip by consulting specialists")
                .instruction("""
                        You plan trips for passengers.

                        Use the fare-agent tool for prices and the weather-agent tool for
                        conditions. Call both when the question needs both.

                        Write the final answer yourself, in at most three lines.""")
                .model(ModelFactory.current())

                // The only difference from the agent above.
                .tools(
                        AgentTool.create(fareAgent),
                        AgentTool.create(weatherAgent))

                .build();

        System.out.println("\n########## Pattern B: agent as tool ##########");
        ask(coordinator, "What does Pune to Goa cost, and what is the weather like there?");

    }

    private static void ask(LlmAgent agent, String question) {

        InMemoryRunner runner = new InMemoryRunner(agent, APP_NAME);
        Session session = runner.sessionService()
                .createSession(APP_NAME, "student-1").blockingGet();

        System.out.println("\nyou: " + question);

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(event -> {

                    event.functionCalls().forEach(call -> {
                        String name = call.name().orElse("?");
                        if (name.equals("transfer_to_agent")) {
                            System.out.println("  [transfer] -> "
                                    + call.args().orElse(Map.of()).get("agent_name"));
                        } else {
                            System.out.println("  [calls agent] " + name);
                        }
                    });

                    if (event.finalResponse()
                            && event.functionCalls().isEmpty()
                            && event.functionResponses().isEmpty()) {

                        String text = event.stringifyContent().strip().replace("\n", " ");
                        if (!text.isEmpty()) {
                            System.out.println("  " + event.author() + ": " + text);
                        }
                    }
                });

        runner.close().blockingAwait();
    }
}
