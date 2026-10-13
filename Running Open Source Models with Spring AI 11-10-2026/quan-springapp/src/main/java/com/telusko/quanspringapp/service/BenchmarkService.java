package com.telusko.quanspringapp.service;



import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.telusko.quanspringapp.quiz.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


//Identifies the Q4 model.
//Loads and warms up the model.
//Sends all 15 questions.
//Collects responses.
//Checks which answers are correct.
//Calculates the score.
//Measures generation speed.
//Retrieves the model size.
//Returns the complete result.

/**
 * Runs the same quiz against each quantisation and reports numbers, not impressions.
 *
 * <p>One ChatClient serves all three. Only the model name changes per call, which is
 * the point worth making in class: nothing in this code knows or cares that it is
 * talking to a shrunken model.
 */
@Service
public class BenchmarkService {

    /** One question answered by one variant. */
    public record Answer(String questionId, String prompt, String reply,
                         boolean correct, long millis, int outputTokens) {}

    /** Everything measured about one variant. */
    public record Result(String quant, String model, double sizeGb,
                         int correct, int total, int correctHard, int totalHard,
                         double tokensPerSecond, long totalMillis,
                         List<Answer> answers) {}

    /** How fast the model writes, measured on one long answer. */
    public record Speed(int tokens, long millis, double tokensPerSecond) {}

    private final ChatClient chat;
    private final RestClient ollama;
    private final List<String> quants;
    private final String repo;

    public BenchmarkService(ChatClient.Builder builder,
                            @Value("${quantlab.repo}") String repo,
                            @Value("${quantlab.quants}") List<String> quants,
                            @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
                            String ollamaUrl) {

        this.repo = repo;
        this.quants = quants;
        this.ollama = RestClient.create(ollamaUrl);

        this.chat = builder
                .defaultSystem(Quiz.SYSTEM)
                .build();
    }

    public List<String> quants() {
        return quants;
    }

    /** The Ollama model name for a quantisation, for example repo:Q4_K_M. */
    public String modelName(String quant) {
        return repo + ":" + quant;
    }

    /**
     * Runs every question against one variant.
     *
     * <p>The first question of a run also pays for loading the model into memory, so
     * warmUp() is called first and its timing is thrown away. Without that the biggest
     * file looks slower than it is, and the comparison is about disk speed rather than
     * about the model.
     */
    public Result run(String quant) {

        String model = modelName(quant);
        warmUp(model);

        List<Answer> answers = new ArrayList<>();
        long totalMillis = 0;

        for (Quiz.Question question : Quiz.QUESTIONS) {

            long started = System.currentTimeMillis();
            ChatResponse response = ask(model, question.prompt());
            long took = System.currentTimeMillis() - started;

            String reply = response.getResult().getOutput().getText().strip();
            int tokens = tokensOut(response);

            totalMillis += took;

            answers.add(new Answer(question.id(), question.prompt(), reply,
                    question.isCorrect(reply), took, tokens));
        }

        int correct = (int) answers.stream().filter(Answer::correct).count();

        int totalHard = (int) Quiz.QUESTIONS.stream().filter(Quiz.Question::hard).count();
        int correctHard = (int) answers.stream()
                .filter(a -> hard(a.questionId()) && a.correct())
                .count();

        // Generation speed is measured separately, on purpose.
        //
        // The quiz answers are one or two words each, so timing them mostly measures
        // round-trip overhead, not how fast the model writes. Divide six tiny answers
        // by six round trips and every variant looks equally slow, which is not the
        // thing we came to compare. One long answer gives the real number.
        Speed speed = measureSpeed(model);

        return new Result(quant, model, sizeGb(model),
                correct, Quiz.QUESTIONS.size(), correctHard, totalHard,
                speed.tokensPerSecond(), totalMillis, answers);
    }

    /**
     * One long answer, timed. Tokens written divided by seconds spent writing.
     *
     * <p>This is the number people mean by "how fast is the model", and the one that
     * changes most between quantisations: fewer bits per weight means less memory to
     * move for every single token.
     */
    private Speed measureSpeed(String model) {

        long started = System.currentTimeMillis();

        ChatResponse response = chat.prompt()
                .system("Write in full sentences. Do not stop early.")
                .user("Explain in about 150 words what a Java interface is and when to use one.")
                .options(OllamaChatOptions.builder()
                        .model(model)
                        .temperature(0.0)
                        .build())
                .call()
                .chatResponse();

        long millis = System.currentTimeMillis() - started;
        int tokens = tokensOut(response);

        return new Speed(tokens, millis,
                millis == 0 ? 0 : round(tokens / (millis / 1000.0)));
    }

    // ------------------------------------------------------------------ helpers

    private ChatResponse ask(String model, String prompt) {
        return chat.prompt()
                .user(prompt)
                // The only line that differs between the three runs.
                .options(OllamaChatOptions.builder()
                        .model(model)
                        .temperature(0.0)
                        .build())
                .call()
                .chatResponse();
    }

    /** One throwaway call, so loading the file off disk is not counted as thinking time. */
    private void warmUp(String model) {
        try {
            ask(model, "Say ok.");
        } catch (Exception ignored) {
            // A failure here will fail again in the real run, where it is reported properly.
        }
    }

    private static boolean hard(String questionId) {
        return Quiz.QUESTIONS.stream()
                .anyMatch(q -> q.id().equals(questionId) && q.hard());
    }

    private static int tokensOut(ChatResponse response) {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return 0;
        }
        Integer out = response.getMetadata().getUsage().getCompletionTokens();
        return out == null ? 0 : out;
    }

    /**
     * How big the file actually is on disk, asked of Ollama rather than hard coded.
     *
     * <p>This is the number students remember, so it should be real.
     */
    @SuppressWarnings("unchecked")
    private double sizeGb(String model) {
        try {
            Map<String, Object> body = ollama.get().uri("/api/tags")
                    .retrieve().body(Map.class);

            List<Map<String, Object>> models =
                    (List<Map<String, Object>>) body.get("models");

            return models.stream()
                    .filter(m -> model.equals(m.get("name"))
                            || (model + ":latest").equals(m.get("name")))
                    .map(m -> ((Number) m.get("size")).doubleValue() / 1e9)
                    .findFirst()
                    .map(BenchmarkService::round)
                    .orElse(0.0);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
