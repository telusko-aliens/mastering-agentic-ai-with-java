package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class CallBacks
{
    private static final String APP_NAME = "telusko-adk";
    private static final Map<String, String> CACHE = new HashMap<>();

    private static final String[] lastQuestion = {""};

    public static void main(String[] args)
    {
        LlmAgent agent = LlmAgent.builder()
                .name("support-agent")
                .description("Answers questions about a booking")
                .instruction("""
                                You are an airline support agent.

                                Use getPaymentDetails when asked about
                                how a booking was paid for.

                                Answer in two lines.
                                """).model(ModelFactory.current())
                .tools(FunctionTool.create(CallBacks.class, "getPaymentDetails"))
                // before calling the model check if we already have to answer
                .beforeModelCallback(
                        (context, requestBuilder) -> {

                            String question = lastUserText(requestBuilder.build().contents());
                            if (question.isEmpty()) {
                                return Maybe.empty();
                            }
                            String cached = CACHE.get(question);
                            if (cached != null) {
                                System.out.println("  [cache hit] the model was not called");
                                return Maybe.just(LlmResponse.builder()
                                        .content(Content.fromParts(Part.fromText(cached)))
                                        .build());
                            }

                            System.out.println("  [cache miss] calling the model");
                            return Maybe.empty();
                        })
                //after model --> save its response in our cache
                .afterModelCallback(
                        (context, response) -> {
                            String text =
                                    response.content()
                                            .flatMap(
                                                    c -> c.parts()
                                            )

                                            .map(parts ->
                                                    parts.isEmpty()
                                                            ? ""
                                                            : parts.get(0)
                                                            .text()
                                                            .orElse("")
                                            )

                                            .orElse("");
                            if (!text.isBlank()) {

                                CACHE.put(
                                        lastQuestion[0],
                                        text
                                );
                            }
                            return Maybe.empty();
                        })
                // cleaning output so tool may return full card info and we don't want model to receive it
                .afterToolCallback(
                        (
                                invocation,
                                tool,
                                toolArgs,
                                toolContext,
                                result
                        ) -> {

                            if (!(result instanceof Map<?, ?> map)
                                    || !map.containsKey("card")) {

                                return Maybe.empty();
                            }


                            /*
                             * Copy result so we can modify it.
                             */
                            Map<String, Object> cleaned =
                                    new LinkedHashMap<>();


                            map.forEach(
                                    (key, value) ->
                                            cleaned.put(
                                                    String.valueOf(key),
                                                    value
                                            )
                            );


                            String card =
                                    String.valueOf(
                                            cleaned.get("card")
                                    );


                            /*
                             * Keep only last four digits.
                             */
                            cleaned.put(
                                    "card",
                                    "**** **** **** "
                                            + card.substring(
                                            card.length() - 4
                                    )
                            );


                            System.out.println(
                                    "  [redacted] card number masked "
                                            + "before the model saw it"
                            );


                            /*
                             * Return changed tool result.
                             *
                             * The LLM sees CLEANED, not original.
                             */
                            return Maybe.just(cleaned);})
                .build();

        InMemoryRunner runner =
                new InMemoryRunner(
                        agent,
                        APP_NAME
                );

        String question = "How was booking TL1001 paid for?";
        System.out.println("First time asking: ");
        ask(runner, question);
        System.out.println("second time time asking: ");
        ask(runner, question);



        runner.close()
                .blockingAwait();
    }


    private static String lastUserText(java.util.List<Content> contents) {

        for (int i = contents.size() - 1; i >= 0; i--) {
            Content content = contents.get(i);
            if (content.role().orElse("").equals("user")) {
                String text = content.parts()
                        .map(parts -> parts.isEmpty() ? "" : parts.get(0).text().orElse(""))
                        .orElse("");
                if (!text.isBlank()) {
                    lastQuestion[0] = text;
                    return text;
                }
            }
        }
        return "";
    }

    /** Returns a full card number on purpose, so the redaction callback has work to do. */
    @Annotations.Schema(description = "Get the payment details for a booking, including the card used.")
    public static Map<String, Object> getPaymentDetails(
            @Annotations.Schema(name = "pnr", description = "The six character PNR code") String pnr) {

        return Map.of(
                "pnr", pnr,
                "amount", 4500,
                "method", "Credit card",
                "card", "4539148803436467");
    }

    private static void ask(InMemoryRunner runner, String question) {

        // A fresh session each time, so the second run is a genuine repeat and not a follow-up
        // the model answers from the transcript.
        Session session = runner.sessionService()
                .createSession(APP_NAME, "student-1").blockingGet();

        System.out.println("you: " + question);

        long started = System.currentTimeMillis();

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(event -> {
                    if (event.finalResponse()
                            && event.functionCalls().isEmpty()
                            && event.functionResponses().isEmpty()) {

                        String text = event.stringifyContent().strip().replace("\n", " ");
                        if (!text.isEmpty()) {
                            System.out.println("  agent: " + text);
                        }
                    }
                });

        System.out.println("  took " + (System.currentTimeMillis() - started) + " ms");
    }
}
