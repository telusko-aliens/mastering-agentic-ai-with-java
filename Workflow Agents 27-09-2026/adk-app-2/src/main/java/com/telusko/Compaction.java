package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.LlmEventSummarizer;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.Map;

public class Compaction
{
    private static final String APP_NAME =
            "telusko_adk";

    private static final String USER =
            "student-1";

    public static void main(String[] args)
    {
        System.out.println("Model: " + ModelFactory.describe());
        System.out.println();

        LlmAgent agent = LlmAgent.builder()
                .name("long-chat-agent")
                .description("An assistant used for a long conversation")
                .instruction("""
                        You are a travel assistant.
                        Answer in one short sentence. Never more.""")
                .model(ModelFactory.current())
                .build();

        //Compaction config
        EventsCompactionConfig compaction = EventsCompactionConfig.builder()
                .compactionInterval(4)// consider compacting conversation events roughly for every 4 events
                //8 events
                //.tokenThreshold(75_000) its if we want 75% of context window filled
                .overlapSize(1)
                .summarizer(
                        new LlmEventSummarizer(
                                ModelFactory.current()
                        )
                )
                .build();

        App app = App.builder()
                .name(APP_NAME)
                .rootAgent(agent)
                .eventsCompactionConfig(compaction)
                .build();

        BaseSessionService sessions = new InMemorySessionService();

        Runner runner = Runner.builder()
                .app(app)
                .sessionService(sessions)
                .build();

        Session session = sessions.createSession(new SessionKey(APP_NAME, USER, null), Map.of())
                .blockingGet();

        String[] conversation = {
                "I am flying from Pune to Goa on 2 October.",
                "My booking reference is TL1001.",
                "I want a window seat.",
                "I am travelling with one checked bag of 15 kilos.",
                "I am vegetarian, so please note the meal.",
                "I will need wheelchair assistance at Goa.",
        };

        for (String line : conversation) {
            ask(runner, session, line);
        }

    }

    private static void ask(Runner runner, Session session, String question) {

        System.out.println("  you  : " + question);
        System.out.print("  agent: ");

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(event -> {
                    if (event.finalResponse()) {
                        System.out.println(event.stringifyContent().strip().replace("\n", " "));
                    }
                });
    }
}
