package com.telusko.rag_eval_langsmith.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telusko.rag_eval_langsmith.eval.EvaluationRunner;
import com.telusko.rag_eval_langsmith.eval.LangSmithEvaluationPublisher;

@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    private final EvaluationRunner runner;
    private final LangSmithEvaluationPublisher langSmith;   // NEW

    public EvaluationController(EvaluationRunner runner,
                                LangSmithEvaluationPublisher langSmith) {
        this.runner = runner;
        this.langSmith = langSmith;
    }

    // NEW: GET /api/evaluation/langsmith
    // Tells the UI whether uploading to LangSmith is on, and gives the last
    // experiment link for each topK, e.g. {"enabled":true,"experiments":{"3":"https://..."}}
    @GetMapping("/langsmith")
    public Map<String, Object> langSmith() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", langSmith.enabled());
        out.put("experiments", langSmith.lastLinks());
        return out;
    }

    // POST /api/evaluation/run?topK=3   (topK defaults to 3 if not given)
    // Takes about a minute: 12 questions, each with up to 3 judge calls.
    @PostMapping("/run")
    public EvaluationRunner.Experiment run(
            @RequestParam(name = "topK", defaultValue = "3") int topK) {
        return runner.run(topK);
    }

    // GET /api/evaluation/results -> the saved runs, keyed by topK.
    // The UI calls this on page load so old results show up instantly.
    @GetMapping("/results")
    public Map<Integer, EvaluationRunner.Experiment> results() {
        return runner.lastResults();
    }

    // GET /api/evaluation/dataset -> the 12 questions as JSON.
    @GetMapping("/dataset")
    public Object dataset() {
        return runner.dataset();
    }

    /**
     * POST /api/evaluation/compare
     * Runs the same 12 questions at topK 2, then at topK 5, and puts the two
     * report cards side by side with a one-line verdict.
     *
     * One after the other on purpose: running both at once would make them
     * queue at the same model, and the latency would measure queueing.
     * Takes about two minutes.
     */
    @PostMapping("/compare")
    public Map<String, Object> compare() {

        EvaluationRunner.Experiment small = runner.run(2);
        EvaluationRunner.Experiment large = runner.run(5);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experimentA", small);
        out.put("experimentB", large);
        out.put("verdict", verdict(small, large));
        return out;
    }

    /**
     * Writes a one-sentence conclusion based ONLY on the numbers, so we never
     * claim something the run didn't actually show.
     *
     * passDelta = how many more cases topK 5 passed than topK 2 (can be negative)
     * slower    = how many ms slower topK 5 was on average
     */
    private static String verdict(EvaluationRunner.Experiment a,
                                  EvaluationRunner.Experiment b) {

        int passDelta = b.passed() - a.passed();
        long slower = b.averageMillis() - a.averageMillis();

        // The speed part of the sentence.
        String speed = slower > 0
                ? "topK=5 was %d ms slower per question".formatted(slower)
                : "topK=5 was not slower";

        // Case 1: topK 5 did better. ("case" vs "cases" for correct English.)
        if (passDelta > 0) {
            return "topK=5 passed %d more case%s. %s."
                    .formatted(passDelta, passDelta == 1 ? "" : "s", speed);
        }
        // Case 2: topK 5 did worse -> extra chunks were noise.
        if (passDelta < 0) {
            return ("topK=5 passed %d fewer case%s: the extra chunks added noise rather "
                    + "than evidence. %s.")
                    .formatted(-passDelta, passDelta == -1 ? "" : "s", speed);
        }
        // Case 3: same result -> more retrieval bought nothing.
        return "Both passed %d of %d. More retrieval bought nothing here. %s."
                .formatted(a.passed(), a.cases(), speed);
    }
}
