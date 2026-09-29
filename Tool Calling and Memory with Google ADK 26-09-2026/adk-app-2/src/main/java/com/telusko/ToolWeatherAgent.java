package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

public class ToolWeatherAgent
{
    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args)
    {
        System.out.println("Model: " + ModelFactory.describe());
        System.out.println();
        LlmAgent agent = LlmAgent.builder()
                .name("travel-agent")
                .description("Helps passengers with bookings and travel conditions")
                .instruction(
                        """
                                                        You are a travel assistant for an airline.
                                                        Use getBooking for anything about a booking.
                                                        Use getWeather when the passenger asks about weather conditions at a city.
                                                        Answer in at most four lines. Never invent a temperature or a booking.
                                """
                )
                .model(ModelFactory.current())
                .tools(
                        FunctionTool.create(BookingTools.class, "getBooking"),
                        FunctionTool.create(WeatherTools.class, "getWeather")
                )
                .build();

        InMemoryRunner runner = new InMemoryRunner(agent, APP_NAME);
        Session session = runner.sessionService().createSession(APP_NAME, "student-1").blockingGet();
        ask(runner, session, "What is the Weather in Bangkok right now");

    }

    private static void ask(InMemoryRunner runner, Session session, String question) {

        System.out.println("=== " + question);

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(ToolWeatherAgent::show);

        System.out.println();
    }

    private static void show(Event event) {

        event.functionCalls().forEach(call ->
                System.out.println("  [tool call]     " + call.name().orElse("?")
                        + " " + call.args().orElse(java.util.Map.of())));

        event.functionResponses().forEach(response ->
                System.out.println("  [tool result]   " + response.response().orElse(java.util.Map.of())));

        if (event.finalResponse()) {
            System.out.println("  [answer]        " + event.stringifyContent().strip());
        }
    }
}
