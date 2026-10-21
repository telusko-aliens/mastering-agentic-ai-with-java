package com.telusko.rag_eval_langsmith.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telusko.rag_eval_langsmith.service.RagService;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

@Service
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    private final RagService rag;               // the real RAG pipeline we are testing
    private final RagEvaluators evaluators;     // the 4 scores
    private final Tracer tracer;                // to create the "rag.evaluation" span
    private final EvaluationDataset dataset;    // the 12 questions, loaded from JSON

    // NEW: uploads each finished run to LangSmith (does nothing if there is no key).
    private final LangSmithEvaluationPublisher langSmith;

    // Saved results of the last run for each topK, e.g. {3: Experiment, 5: Experiment}.
    // Kept in memory so reloading the web page doesn't need a new (slow, paid) run.
    // AtomicReference = a thread-safe box: two web requests at once can't corrupt it.
    private final AtomicReference<Map<Integer, Experiment>> results =
            new AtomicReference<>(Map.of());

    // Spring injects the services, and the JSON file from src/main/resources.
    // "throws Exception" because reading/parsing the file could fail.
    public EvaluationRunner(RagService rag,
                            RagEvaluators evaluators,
                            Tracer tracer,
                            LangSmithEvaluationPublisher langSmith,   // NEW
                            @Value("classpath:evaluation-dataset.json") Resource datasetFile)
            throws Exception {

        this.rag = rag;
        this.evaluators = evaluators;
        this.tracer = tracer;
        this.langSmith = langSmith;   // NEW

        // Jackson's ObjectMapper reads the JSON file into our EvaluationDataset record.
        // Done once, at startup.
        try (var in = datasetFile.getInputStream()) {
            this.dataset = new ObjectMapper().readValue(in, EvaluationDataset.class);
        }
        log.info("loaded dataset '{}' with {} cases", dataset.name(), dataset.cases().size());
    }

    /**
     * The result of ONE question: what was asked, what was expected,
     * what the app said, which chunks/sources it used, and its Scores.
     * This becomes one row (plus the Q/A line) in the UI's table.
     */
    public record CaseResult(String id,
                             String category,
                             String question,
                             String expected,
                             String actual,
                             List<String> sources,
                             int chunksRetrieved,
                             boolean answerable,
                             RagEvaluators.Scores scores) {}

    /**
     * The report card for ONE full run of all 12 questions at one topK.
     * passed            - how many cases passed (e.g. 11 of 12)
     * correctness/...   - AVERAGE score over all cases
     * refusalAccuracy   - fraction of cases where refusal was right
     * averageMillis     - average answer time
     * details           - every CaseResult
     */
    public record Experiment(int topK,
                             int cases,
                             int passed,
                             double correctness,
                             double faithfulness,
                             double relevance,
                             double refusalAccuracy,
                             long averageMillis,
                             List<CaseResult> details) {}

    // Lets the controller show the questions (GET /api/evaluation/dataset).
    public EvaluationDataset dataset() {
        return dataset;
    }

    // Lets the controller show the saved runs (GET /api/evaluation/results).
    public Map<Integer, Experiment> lastResults() {
        return results.get();
    }

    /**
     * Runs all 12 cases at the given topK and returns the report card.
     *
     * ONE BY ONE on purpose (not in parallel). Parallel would be faster, but
     * all 12 would queue at OpenAI together and the latency numbers would
     * measure waiting in line, not our retrieval.
     */
    public Experiment run(int topK) {

        // One span for the whole run. Every question's rag.retrieval and
        // rag.generation spans become CHILDREN of this, so in LangSmith you
        // see one tree for the entire evaluation.
        // NEW: remember when the run started. LangSmith uses it for the
        // experiment's name and the timestamps of each run.
        Instant startedAt = Instant.now();

        Span span = tracer.nextSpan().name("rag.evaluation").start();
        List<CaseResult> details = new ArrayList<>();

        try (var ignored = tracer.withSpan(span)) {
            span.tag("eval.dataset", dataset.name());
            span.tag("eval.top_k", String.valueOf(topK));
            span.tag("eval.cases", String.valueOf(dataset.cases().size()));

            // ---------------------------------------------------- the main loop
            for (EvaluationDataset.Case testCase : dataset.cases()) {

                // 1. Ask the REAL RAG pipeline (same method the chat page uses).
                RagService.Answer answer = rag.ask(testCase.question(), topK);

                // 2. Mark the answer: 4 scores + pass/fail.
                RagEvaluators.Scores scores = evaluators.score(
                        testCase.question(),
                        testCase.expectedAnswer(),
                        answer.answer(),
                        answer.chunks(),
                        testCase.answerable(),
                        answer.millis());

                // 3. Save everything about this case.
                details.add(new CaseResult(
                        testCase.id(),
                        testCase.category(),
                        testCase.question(),
                        testCase.expectedAnswer(),
                        answer.answer(),
                        answer.sources(),
                        answer.chunks().size(),
                        testCase.answerable(),
                        scores));

                // 4. Print a progress line in the console, e.g.
                //    [return-window-standard] topK=3 FAIL correctness=0.0 faith=1.0 rel=1.0
                log.info("  [{}] topK={} {} correctness={} faith={} rel={}",
                        testCase.id(), topK, scores.passed() ? "PASS" : "FAIL",
                        round(scores.correctness()), round(scores.faithfulness()),
                        round(scores.relevance()));
            }

            // Turn 12 case results into one report card (averages etc.).
            Experiment experiment = summarise(topK, details);

            // Put the summary ON the span too, so the scores are visible in
            // LangSmith, not just in our local console.
            span.tag("eval.passed", experiment.passed() + "/" + experiment.cases());
            span.tag("eval.correctness", String.valueOf(experiment.correctness()));
            span.tag("eval.faithfulness", String.valueOf(experiment.faithfulness()));
            span.tag("eval.relevance", String.valueOf(experiment.relevance()));
            span.tag("eval.avg_latency_ms", String.valueOf(experiment.averageMillis()));

            // NEW: copy this run into LangSmith as an EXPERIMENT (dataset + runs + scores).
            // Happens AFTER all 12 questions are answered, so it does not change the
            // latency numbers. If it fails, it only logs a warning and returns null.
            String langSmithLink = langSmith.publish(dataset, topK, startedAt, details, experiment);
            if (langSmithLink != null) {
                // Put the link on the trace too, so you can jump from trace to experiment.
                span.tag("eval.langsmith_experiment", langSmithLink);
            }

            // Save this run next to runs at other topK values, so "compare"
            // and a page reload can show them.
            // Copy the old map -> add/replace this topK -> store an unmodifiable copy.
            Map<Integer, Experiment> updated = new java.util.LinkedHashMap<>(results.get());
            updated.put(topK, experiment);
            results.set(Map.copyOf(updated));

            return experiment;

        } finally {
            span.end();   // always close the span, even if something threw an error
        }
    }

    /**
     * Builds the report card from the 12 CaseResults using Java streams:
     *   passed          -> count cases where passed() is true
     *   correctness ... -> average of that score over all cases
     *   refusalAccuracy -> (cases with correct refusal) / (total cases)
     *   averageMillis   -> average answer time
     * orElse(0) = if the list were empty, use 0 instead of crashing.
     */
    private static Experiment summarise(int topK, List<CaseResult> details) {
        int cases = details.size();
        return new Experiment(
                topK,
                cases,
                (int) details.stream().filter(d -> d.scores().passed()).count(),
                round(details.stream().mapToDouble(d -> d.scores().correctness()).average().orElse(0)),
                round(details.stream().mapToDouble(d -> d.scores().faithfulness()).average().orElse(0)),
                round(details.stream().mapToDouble(d -> d.scores().relevance()).average().orElse(0)),
                round(details.stream().filter(d -> d.scores().refusalCorrect()).count() / (double) cases),
                (long) details.stream().mapToLong(d -> d.scores().millis()).average().orElse(0),
                details);
    }

    // Rounds to 2 decimal places: 0.91666 -> 91.666 -> 92 -> 0.92
    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
