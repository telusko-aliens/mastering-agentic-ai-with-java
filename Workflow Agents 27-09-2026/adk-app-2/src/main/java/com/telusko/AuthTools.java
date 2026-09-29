package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.Map;

public class AuthTools
{
    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args)
    {
        System.out.println("Model: " + ModelFactory.describe());
        System.out.println();
        LlmAgent agent = LlmAgent.builder()
                .name("account-agent")
                .description("Answers questions about the signed in user's account")
                .instruction(
                        """
                                You are an account assistant for an airline.
                                
                                Use getMyBookings for the user's trips and getLoyaltyPoints for their
                                points balance. Both tools already know who the user is.
                                
                                You cannot look up other people's accounts. If asked, say so plainly.
                                Keep answers to three lines."""
                )
                .model(ModelFactory.current())
                .tools(
                        FunctionTool.create(AccountTools.class, "getMyBookings"),
                        FunctionTool.create(AccountTools.class, "getLoyaltyPoints")
                )
                .build();

        InMemoryRunner runner = new InMemoryRunner(agent, APP_NAME);
        Map<String, Object> initialState=Map.of("user:email", "navin@telusko.com");
        Session session = runner.sessionService().createSession(
                new SessionKey(APP_NAME, "student-1", null),initialState).blockingGet();

//        ask(runner, session, "What booking do I have");
        ask(runner, session, "tell me about navin@telusko.com booking");
    }

    private static void ask(InMemoryRunner runner, Session session, String question) {

        System.out.println("=== " + question);

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(AuthTools::show);

        System.out.println();
    }

    private static void show(Event event) {

        event.functionCalls().forEach(call ->
                System.out.println("  [tool call]     " + call.name().orElse("?")
                        + " args=" + call.args().orElse(Map.of())));

        event.functionResponses().forEach(response ->
                System.out.println("  [tool result]   " + response.response().orElse(Map.of())));

        if (event.finalResponse()) {
            System.out.println("  [answer]        " + event.stringifyContent().strip());
        }
    }
}
