package com.telusko.rag_eval_langsmith.eval;

import java.util.List;
import java.util.Locale;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.stereotype.Component;

@Component
public class RagEvaluators
{

    private final ChatClient judge;                     // our own correctness judge
    private final FactCheckingEvaluator faithfulness;   // Spring AI's ready-made faithfulness check
    private final RelevancyEvaluator relevance;         // Spring AI's ready-made relevance check

    public RagEvaluators(ChatClient.Builder builder) {

        // The judge is a PLAIN ChatClient: no RAG advisor, no refund-policy
        // system prompt. The judge must not inherit the rules of the thing
        // it is judging - like a teacher not using the student's notes.
        this.judge = builder.build();

        // Spring AI's faithfulness evaluator. Internally it asks the model:
        // "Is this answer supported by this context?"
        // (Small API quirk: builder(builder) here, chatClientBuilder(builder) below.)
        this.faithfulness = FactCheckingEvaluator.builder(builder)
                .build();

        // Spring AI's relevance evaluator. Internally it asks the model:
        // "Is this answer, with this context, relevant to the question?"
        this.relevance = RelevancyEvaluator.builder()
                .chatClientBuilder(builder)
                .build();
    }

    /**
     * The result card for one question.
     * Scores are 0.0 (bad) to 1.0 (good).
     * refusalCorrect - did it refuse/answer correctly
     * millis         - how long the RAG answer took
     * passed         - overall PASS or FAIL
     * note           - a short human reason when it fails
     */
    public record Scores(double correctness,
                         double faithfulness,
                         double relevance,
                         boolean refusalCorrect,
                         long millis,
                         boolean passed,
                         String note) {}

    /**
     * Marks ONE answer.
     *
     * @param question   the test question
     * @param expected   the correct answer from the dataset
     * @param answered   what our RAG app actually said
     * @param contexts   the chunks our RAG app retrieved
     * @param answerable can the document answer this at all?
     * @param millis     how long the RAG app took
     */
    public Scores score(String question,
                        String expected,
                        String answered,
                        List<String> contexts,
                        boolean answerable,
                        long millis) {

        // ==================================================================
        // SCORE 1: REFUSAL  (plain Java, no AI)
        // ==================================================================
        boolean refused = looksLikeRefusal(answered);

        // Truth table for refusalCorrect = (answerable != refused):
        //   answerable=true,  refused=false -> true   (answered, good)
        //   answerable=true,  refused=true  -> false  (refused a real question, bad)
        //   answerable=false, refused=true  -> true   (refused correctly, good)
        //   answerable=false, refused=false -> false  (made something up, bad)
        boolean refusalCorrect = answerable != refused;

        // ------------------------------------------------------------------
        // SHORTCUT A: unanswerable question.
        // Nothing else to score: there's no correct answer to compare with and
        // no context to be faithful to. Refused = full marks, else zero.
        // ------------------------------------------------------------------
        if (!answerable) {
            return new Scores(
                    refusalCorrect ? 1.0 : 0.0,
                    refusalCorrect ? 1.0 : 0.0,
                    refusalCorrect ? 1.0 : 0.0,
                    refusalCorrect,
                    millis,
                    refusalCorrect,
                    refusalCorrect
                            ? "Correctly refused: the document does not cover this."
                            : "Answered a question the document cannot support.");
        }

        // ------------------------------------------------------------------
        // SHORTCUT B: answerable question, but the app refused.
        // That's already a fail. Calling 3 AI judges would just cost money
        // to agree with us.
        // ------------------------------------------------------------------
        if (refused) {
            return new Scores(0.0, 0.0, 0.0, false, millis, false,
                    "Refused a question the document does answer.");
        }

        // Spring AI's evaluators want Document objects, not plain Strings,
        // so wrap each chunk text in a Document.
        List<Document> docs = contexts.stream().map(Document::new).toList();

        // ==================================================================
        // SCORE 2: CORRECTNESS  (our own LLM judge, method below)
        // Compares OUR answer with the EXPECTED answer.
        // ==================================================================
        double correctness = judgeCorrectness(question, expected, answered);

        // ==================================================================
        // SCORE 3: FAITHFULNESS  (Spring AI)
        // Does the answer follow from the retrieved chunks?
        // Catches the model adding something that sounds right but the
        // document never said (a "hallucination").
        // EvaluationRequest(question, chunks, answer) = everything the judge needs.
        // ==================================================================
        double faith = toScore(faithfulness.evaluate(
                new EvaluationRequest(question, docs, answered)));

        // ==================================================================
        // SCORE 4: RELEVANCE  (Spring AI)
        // Did retrieval find chunks that are actually about the question?
        // Low relevance + high correctness = the model answered from its own
        // knowledge, not from our document.
        // ==================================================================
        double rel = toScore(relevance.evaluate(
                new EvaluationRequest(question, docs, answered)));

        // PASS only if ALL three are good enough:
        //   right (>= 0.7) AND grounded (>= 0.5) AND retrieved well (>= 0.5)
        boolean passed = correctness >= 0.7 && faith >= 0.5 && rel >= 0.5;

        return new Scores(correctness, faith, rel, true, millis, passed,
                passed ? "" : firstProblem(correctness, faith, rel));
    }

    // ------------------------------------------------------------------ the judge

    /**
     * Our LLM judge: asks the model "does this answer mean the same as the
     * correct answer?" and wants back just a number.
     *
     * Why not just compare strings with equals()?
     *   "15 days" and "Fifteen days, because of collection costs" are the
     *   SAME answer but different strings. A model understands meaning.
     *
     * Why ask for a number and not a sentence?
     *   A number is easy for code to use. A paragraph would need parsing.
     */
    private double judgeCorrectness(String question, String expected, String answered) {

        String verdict = judge.prompt()
                // System prompt = the grading rules (a marking scheme).
                .system("""
                        You grade answers. You are strict about facts and relaxed about
                        wording.

                        Reply with one number between 0 and 1 and nothing else.

                        1.0  same meaning, all facts and numbers match
                        0.5  partly right, or right but missing something important
                        0.0  wrong, or contradicts the reference""")
                // User prompt = the actual paper to grade.
                // %s placeholders are filled by .formatted(...) in order.
                .user("""
                        Question:
                        %s

                        Reference answer:
                        %s

                        Answer to grade:
                        %s""".formatted(question, expected, answered))
                .call()
                .content();

        // Turn the reply (e.g. "1.0") into a double.
        return parseScore(verdict);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Spring AI's evaluators return an EvaluationResponse with
     * a pass/fail flag AND a score. Use the score if there is one;
     * otherwise pass = 1.0, fail = 0.0.
     */
    private static double toScore(EvaluationResponse response) {
        if (response == null) {
            return 0.0;
        }
        float score = response.getScore();
        if (score > 0.0f) {
            return Math.min(1.0, score);    // never more than 1.0
        }
        return response.isPass() ? 1.0 : 0.0;
    }

    /**
     * Pulls the first number like 0, 1, 0.5, 1.0 out of the judge's reply.
     * If the judge disobeys and writes a sentence with no number,
     * we score 0 instead of crashing.
     *
     * The regex ([01](?:\.\d+)?) means:
     *   [01]        -> a 0 or a 1
     *   (?:\.\d+)?  -> optionally followed by a dot and more digits
     */
    private static double parseScore(String text) {
        if (text == null) {
            return 0.0;
        }
        var matcher = java.util.regex.Pattern
                .compile("([01](?:\\.\\d+)?)")
                .matcher(text.strip());
        if (!matcher.find()) {
            return 0.0;
        }
        try {
            // Clamp the value between 0.0 and 1.0 just in case.
            return Math.max(0.0, Math.min(1.0, Double.parseDouble(matcher.group(1))));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * The refusal check: an empty answer, or one containing "i don't know"
     * or "i do not know" (any case), counts as a refusal.
     * Locale.ROOT makes toLowerCase() behave the same on every computer.
     */
    private static boolean looksLikeRefusal(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        return lower.contains("i don't know") || lower.contains("i do not know");
    }

    /**
     * For a failed case, explains the FIRST thing that went wrong,
     * checked in order: correctness, then faithfulness, then relevance.
     * This text appears in the "Note" column of the results table.
     */
    private static String firstProblem(double correctness, double faith, double rel) {
        if (correctness < 0.7) {
            return "Wrong answer.";
        }
        if (faith < 0.5) {
            return "Right answer, but it does not follow from the retrieved chunks.";
        }
        return "Retrieval brought back chunks that do not bear on the question.";
    }

}
