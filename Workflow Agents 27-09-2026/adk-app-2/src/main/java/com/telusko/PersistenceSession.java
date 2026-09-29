package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public class PersistenceSession
{
    private static final String APP_NAME = "telusko-adk";
    private static final String USER = "student-1";

    /** A fixed id so the second run can find the first run's conversation. */
    private static final String SESSION_ID = "demo-session";

    public static void main(String[] args) {
        System.out.println("Model: " + ModelFactory.describe());

        Path dbFile = Path.of(System.getProperty("user.dir"), "data", "sessions");
        String jdbcUrl = "jdbc:h2:file:" + dbFile.toString().replace('\\', '/') + ";AUTO_SERVER=TRUE";

        System.out.println("Database: " + dbFile + ".mv.db");
        System.out.println();

        BaseSessionService sessions = new JdbcSessionService(jdbcUrl);

        //agent
        LlmAgent agent = LlmAgent.builder()
                .name("persistent-agent")
                .description("An assistant whose conversations survive a restart")
                .instruction("""
                        You are an airline assistant.

                        Save a seat preference with saveSeatPreference when the passenger
                        mentions one. Refer back to earlier messages when it is relevant.

                        Keep answers to two lines.""")
                .model(ModelFactory.current())
                .tools(
                        FunctionTool.create(PreferenceTools.class, "saveSeatPreference"),
                        FunctionTool.create(PreferenceTools.class, "getPreferences"))
                .build();

        //runner

        Runner runner = Runner.builder()
                .agent(agent)
                .appName(APP_NAME)
                .sessionService(sessions)
                .build();


        //making call llm
        Session session = sessions
                .getSession(APP_NAME, USER, SESSION_ID, Optional.empty())
                .blockingGet();

        boolean firstRun = (session == null);

        if (firstRun) {
            System.out.println(">>> FIRST RUN. Creating a new conversation.\n");

            session = sessions
                    .createSession(new SessionKey(APP_NAME, USER, SESSION_ID), Map.of())
                    .blockingGet();

            ask(runner, session, "My name is Shramik and I always want a window seat.");
            ask(runner, session, "I am flying to Goa next week.");

            System.out.println("Now run this class again. Nothing is in memory any more.");

        } else {
            System.out.println(">>> SECOND RUN. Loaded from the database: "
                    + session.events().size() + " events already on record.\n");

            // Nothing in this process has seen those messages. They came off disk, and the
            // runner replays them to the model exactly as if the chat never stopped.
            ask(runner, session, "What is my name and where am I flying?");
            ask(runner, session, "And what seat do I prefer?");
        }

        System.out.println();
        report(sessions, session);

    }

    private static void ask(Runner runner, Session session, String question) {

        System.out.println("  you  : " + question);
        System.out.print("  agent: ");

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(event -> {
                    if (event.finalResponse()) {
                        System.out.println(event.stringifyContent().strip().replace("\n", "\n         "));
                    }
                });
    }

    private static void report(BaseSessionService sessions, Session session) {

        Session current = sessions
                .getSession(APP_NAME, session.userId(), session.id(), Optional.empty())
                .blockingGet();

        if (current == null) {
            return;
        }

        StringBuilder state = new StringBuilder();
        current.state().forEach((key, value) ->
                state.append(state.isEmpty() ? "" : ", ").append(key).append('=').append(value));

        System.out.println("Stored in the database: " + current.events().size()
                + " events, state {" + state + "}");
    }
}
