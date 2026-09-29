package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.Map;
import java.util.Optional;

public class SessionState
{
    private static final String APP_NAME = "telusko-adk";
    private static final String USER = "student-1";

    public static void main(String[] args) {
        System.out.println("Model: " + ModelFactory.describe());
        System.out.println();

        LlmAgent agent = LlmAgent.builder()
                .name("preference-agent")
                .description("Remembers what the passenger prefers")
                .instruction("""
                        You are an airline assistant.

                        When the passenger tells you a seat preference, save it with
                        saveSeatPreference. Use getPreferences to recall what you know.

                        Keep answers to two lines.""")
                .model(ModelFactory.current())
                .tools(
                        FunctionTool.create(PreferenceTools.class, "saveSeatPreference"),
                        FunctionTool.create(PreferenceTools.class, "getPreferences"))

                .build();
        // in memory session service
        BaseSessionService sessions = new InMemorySessionService();

        Runner runner = Runner.builder()
                .agent(agent)
                .appName(APP_NAME)
                .sessionService(sessions)
                .build();

        Session first = sessions.createSession(new SessionKey(APP_NAME, USER, null),
                        Map.of()).blockingGet();

        ask(runner, first, "I always want a window seat.");
        dumpState(sessions, first);


        Session second = sessions.createSession(new SessionKey(APP_NAME, USER, null), Map.of())
                .blockingGet();
        System.out.println("Session 2: " + second.id() + "   (a different conversation)");
        ask(runner, second, "What do you remember about me?");
        dumpState(sessions, second);


//        Session second = sessions.createSession(new SessionKey(APP_NAME, USER, null), Map.of())
//                .blockingGet();

        runner.close().blockingAwait();

    }
    private static void ask(Runner runner, Session session, String question) {

        System.out.println("  you  : " + question);
        System.out.print("  agent: ");

        runner.runAsync(session.userId(),
                        session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(event -> {
                    if (event.finalResponse()) {
                        System.out.println(event.stringifyContent().strip().replace("\n", "\n         "));
                    }
                });
    }

    private static void dumpState(BaseSessionService sessions, Session session) {

        Session current = sessions
                .getSession(APP_NAME, session.userId(), session.id(), Optional.empty())
                .blockingGet();

        if (current == null) {
            System.out.println("  (session not found)\n");
            return;
        }

        // State implements ConcurrentMap but does not override toString, so printing it
        // directly gives com.google.adk.sessions.State@c33907a4. Walk the entries instead.
        StringBuilder state = new StringBuilder();
        current.state().forEach((key, value) ->
                state.append(state.isEmpty() ? "" : ", ").append(key).append('=').append(value));

        System.out.println("  events: " + current.events().size()
                + "   state: {" + state + "}");
        System.out.println();
    }


}
