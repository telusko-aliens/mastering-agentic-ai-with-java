package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.ExitLoopTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

public class AgentLoop
{
    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args)
    {
        System.out.println("Model: " + ModelFactory.describe());
        System.out.println();

        LlmAgent writer = LlmAgent.builder()
                .name("writer")
                .description("Writes a tagline")
                .instruction("""
                        Write one tagline for what the user asked about.

                        Feedback from the last round, if there was one:
                        {feedback?}

                        If there is feedback, fix exactly what it says.
                        Reply with the tagline only. No quotes, no explanation.""")
                .model(ModelFactory.current())
                .outputKey("draft")
                .build();

        LlmAgent critic = LlmAgent.builder()
                .name("critic")
                .description("Approves the tagline or says what to fix")
                .instruction("""
                        Here is a tagline for an airline:

                        {draft}

                        Judge it against four rules. Be strict: a tagline has to pass all four.
                        1. Four words or fewer. Count them.
                        2. Contains a word about flying: fly, flight, wings, air or landing.
                        3. Does not contain the words India, Bharat, metro or city.
                        4. Not a cliche. "Fly high", "sky is the limit" and "journey of a
                           lifetime" are cliches.

                        If it passes all three, call the exit_loop tool and say APPROVED.
                        If it fails, do NOT call the tool. Reply with one short sentence
                        saying what to fix.""")
                .model(ModelFactory.current())

                // The tool the critic uses to stop the loop. It takes no arguments and returns
                // nothing: its only job is to set a flag the LoopAgent checks after each pass.
                .tools(ExitLoopTool.INSTANCE)

                .outputKey("feedback")
                .build();

        LoopAgent refine = LoopAgent.builder()
                .name("refine")
                .description("Writes a tagline and improves it until a critic approves")
                .subAgents(writer, critic)

                // Three rounds maximum. Without this the critic decides how long you pay for.
                .maxIterations(3)

                .build();


        InMemoryRunner runner = new InMemoryRunner(refine, APP_NAME);
        Session session = runner.sessionService().createSession(APP_NAME, "student-1").blockingGet();


        String brief = "An airline that flies only between small Indian cities.";
        System.out.println("Brief: " + brief + "\n");

        int[] round = {0};

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(brief)))
                .blockingForEach(event -> {

                    // Each pass through the loop starts with the writer, so counting its turns
                    // counts the rounds.
                    if (event.finalResponse() && event.author().equals("writer")) {
                        System.out.println("round " + (++round[0]));
                    }

                    event.functionCalls().forEach(call ->
                            System.out.println("    [tool] " + call.name().orElse("?")
                                    + "   <- the critic is stopping the loop"));

                    // Skip the function-response event. stringifyContent() on that one prints
                    // the raw FunctionResponse object, which is noise. Only text matters here.
                    if (event.finalResponse() && event.functionResponses().isEmpty()) {
                        String text = event.stringifyContent().strip().replace("\n", " ");
                        if (!text.isEmpty()) {
                            System.out.println("  " + event.author() + ": " + text);
                        }
                    }
                });

        System.out.println("\nRounds used: " + round[0] + " of 3");

        runner.close().blockingAwait();



    }
}
