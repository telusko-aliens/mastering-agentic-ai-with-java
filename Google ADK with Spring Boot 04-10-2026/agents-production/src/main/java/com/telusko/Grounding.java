package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.GoogleSearchTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

public class Grounding
{
    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args)
    {
        // ---- without grounding -------------------------------------------
        LlmAgent plain = LlmAgent.builder()
                .name("plain-agent")
                .description("Answers from training data only")
                .instruction("Answer in two lines. Do not say you cannot browse.")
                .model(ModelFactory.current())
                .build();

//        System.out.println("########## no grounding ##########");
//        ask(plain, "What is Google ADK for Java, and what is its latest version?");
        LlmAgent grounded = LlmAgent.builder()
                .name("grounded-agent")
                .description("Answers using Google Search")
                .instruction("""
                        Search before answering anything that could have changed recently.
                        Answer in two lines and say where the information came from.""")
                .model(ModelFactory.current())
                .tools(GoogleSearchTool.INSTANCE)
                .build();

        System.out.println("\n########## with Google Search ##########");
        ask(grounded, "What is Google ADK for Java, and what is its latest version?");



    }

    private static void ask(LlmAgent agent, String question) {

        InMemoryRunner runner = new InMemoryRunner(agent, APP_NAME);
        Session session = runner.sessionService()
                .createSession(APP_NAME, "student-1").blockingGet();

        System.out.println("you: " + question);

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(event -> {

                    if (event.finalResponse()
                            && event.functionCalls().isEmpty()
                            && event.functionResponses().isEmpty()) {

                        String text = event.stringifyContent().strip();
                        if (!text.isEmpty()) {
                            System.out.println("  agent: " + text.replace("\n", "\n         "));
                        }
                    }

                    // The proof that a search happened. No function call event appears for a
                    // built-in tool, so this is where you look.
                    event.groundingMetadata().ifPresent(metadata -> {

                        metadata.webSearchQueries().ifPresent(queries ->
                                System.out.println("  [searched] " + queries));

                        metadata.groundingChunks().ifPresent(chunks -> {
                            System.out.println("  [sources] " + chunks.size());
                            chunks.stream().limit(3).forEach(chunk ->
                                    chunk.web().ifPresent(web ->
                                            System.out.println("    - "
                                                    + web.title().orElse("?"))));
                        });
                    });
                });

        runner.close().blockingAwait();
        System.out.println();
    }
}
