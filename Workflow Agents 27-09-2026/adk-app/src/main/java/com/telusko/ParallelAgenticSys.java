package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.time.Duration;
import java.time.Instant;

public class ParallelAgenticSys
{
    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args)
    {
        System.out.println("Model: " + ModelFactory.describe());
        System.out.println();

        LlmAgent weather = researcher(
                "weather-agent",
                "weather",
                "typical weather and what to pack"
        );

        LlmAgent fares = researcher(
                "fare-agent",
                "fares",
                "how flight prices usually behave for this route and season"
        );


        LlmAgent activities = researcher(
                "activity-agent",
                "activities",
                "three things worth doing"
        );

        ParallelAgent researchAgent = ParallelAgent.builder()
                .name("research")
                .description("Look uo weather, fares activities at the same time")
                .subAgents(weather, fares, activities)
                .build();

        LlmAgent planner = LlmAgent.builder()

                .name("planner")

                .description(
                        "Turns the research into one short travel brief")

                .instruction("""
                        Three researchers looked into this trip.

                        Weather:
                        {weather}

                        Fares:
                        {fares}

                        Activities:
                        {activities}

                        Write one travel brief of at most six lines. Use all three. Do not
                        repeat a point twice and do not mention the researchers.""")

                .model(ModelFactory.current())

                .outputKey("brief")

                .build();

        SequentialAgent trip = SequentialAgent.builder()
                .name("trip-planner")
                .description("researches a trip in a parallel, then writes a brief")
                .subAgents(researchAgent
                ,planner)
                .build();


        InMemoryRunner runner = new InMemoryRunner(trip, APP_NAME);
        Session session = runner.sessionService().createSession(APP_NAME, "student-1").blockingGet();
        String question =
                "I am going to Goa in December for four days.";

        System.out.println(
                "Passenger: " + question + "\n"
        );
        Instant started = Instant.now();


        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(event -> {
                    if (event.finalResponse()) {
                        long ms = Duration.between(started, Instant.now()).toMillis();
                        System.out.println("--- " + event.author() + "  (+" + ms + " ms) ---");
                        System.out.println(event.stringifyContent().strip().indent(2));
                    }
                });

        runner.close().blockingAwait();



    }

    private static LlmAgent researcher(String name, String outputKey, String subject) {

        return LlmAgent.builder()
                .name(name)
                .description("Researches " + subject)
                .instruction("For the trip the user describes, give " + subject + "."
                        + " Three short bullet points, no preamble.")
                .model(ModelFactory.current())
                .outputKey(outputKey)
                .build();
    }
}
