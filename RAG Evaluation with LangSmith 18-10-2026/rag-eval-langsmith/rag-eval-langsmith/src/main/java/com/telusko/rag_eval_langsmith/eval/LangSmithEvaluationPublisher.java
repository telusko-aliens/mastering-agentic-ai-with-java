package com.telusko.rag_eval_langsmith.eval;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class LangSmithEvaluationPublisher 
{
private static final Logger log = LoggerFactory.getLogger(LangSmithEvaluationPublisher.class);

// LangSmith's web UI. Used only to build the "open in LangSmith" link.
private static final String UI_URL = "https://smith.langchain.com";

// LangSmith sorts runs by a "dotted_order" string that starts with the
// start time in exactly this format, e.g. 20261018T022500123456Z
// (year month day T hour minute second microseconds Z).
private static final DateTimeFormatter DOTTED_ORDER_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSSSSS'Z'").withZone(ZoneOffset.UTC);

// Used to give every experiment a unique, readable name: topK-3-20261018-022500
private static final DateTimeFormatter NAME_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

private final String apiKey;     // LangSmith key (PAT or service key)
private final String apiUrl;     // LangSmith API address (EU accounts use a different one)
private final String modelName;  // e.g. gpt-4.1-mini, saved as experiment metadata

// Java's built-in HTTP client. No extra library needed.
private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

// Jackson: turns our Maps into JSON text, and JSON replies into JsonNode trees.
private final ObjectMapper json = new ObjectMapper();

// The last LangSmith link for each topK, e.g. {3: "https://smith..."}.
// The UI reads this to show "Open in LangSmith" links.
private final AtomicReference<Map<Integer, String>> links =
        new AtomicReference<>(Map.of());

/*
 * Reading the key:
 *   ${LANGSMITH_EVAL_API_KEY:${LANGSMITH_API_KEY:}}
 * means "use LANGSMITH_EVAL_API_KEY; if it's missing, use LANGSMITH_API_KEY;
 * if that's missing too, use an empty string (= feature off)".
 *
 * LANGSMITH_ENDPOINT lets EU users switch to https://eu.api.smith.langchain.com
 */
public LangSmithEvaluationPublisher(
        @Value("${LANGSMITH_EVAL_API_KEY:${LANGSMITH_API_KEY:}}") String apiKey,
        @Value("${LANGSMITH_ENDPOINT:https://api.smith.langchain.com}") String apiUrl,
        @Value("${spring.ai.openai.chat.model:unknown}") String modelName) {
    this.apiKey = apiKey;
    this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
    this.modelName = modelName;
}

/** Is the upload switched on? (Only when we have a key.) */
public boolean enabled() {
    return apiKey != null && !apiKey.isBlank();
}

/** The last LangSmith link per topK, for the UI. */
public Map<Integer, String> lastLinks() {
    return links.get();
}

/**
 * Uploads one finished evaluation run to LangSmith.
 *
 * @param dataset  the 12 questions (to create the LangSmith dataset)
 * @param topK     which setting this run used
 * @param started  when the run began (for run timestamps and the name)
 * @param details  the 12 scored cases
 * @param summary  the report card (saved as experiment metadata)
 * @return the link to the experiment in LangSmith, or null if it failed / is off
 */
public String publish(EvaluationDataset dataset,
                      int topK,
                      Instant started,
                      List<EvaluationRunner.CaseResult> details,
                      EvaluationRunner.Experiment summary) {

    // No key -> do nothing. The app works exactly as before.
    if (!enabled()) {
        return null;
    }

    try {
        // ---------------------------------------------- STEP 1: dataset
        String datasetId = findOrCreateDataset(dataset);
        Map<String, String> exampleIds = uploadMissingExamples(datasetId, dataset);

        // ------------------------------------------- STEP 2: experiment
        JsonNode experiment = createExperiment(datasetId, topK, started, summary);
        String experimentId = experiment.path("id").asText();
        String experimentName = experiment.path("name").asText();
        String tenantId = experiment.path("tenant_id").asText("");

        // --------------------------------------------- STEP 3: the runs
        List<String> runIds = createRuns(experimentName, topK, started, details, exampleIds);

        // ------------------------------------------ STEP 4: the scores
        // LangSmith saves runs in the background. Wait a moment so the
        // runs exist before we attach scores to them.
        Thread.sleep(3000);
        for (int i = 0; i < details.size(); i++) {
            attachScores(runIds.get(i), details.get(i).scores());
        }

        // --------------------------------------- STEP 5: mark finished
        send("PATCH", "/api/v1/sessions/" + experimentId,
                obj("end_time", Instant.now().toString()));

        // Build the link to LangSmith's compare page for this experiment.
        // Same link format the official Python SDK prints after an evaluation.
        String link = tenantId.isBlank()
                ? UI_URL
                : UI_URL + "/o/" + tenantId + "/datasets/" + datasetId
                        + "/compare?selectedSessions=" + experimentId;

        // Remember it next to the links of other topK values.
        Map<Integer, String> updated = new LinkedHashMap<>(links.get());
        updated.put(topK, link);
        links.set(Map.copyOf(updated));

        log.info("LangSmith experiment '{}' uploaded: {}", experimentName, link);
        return link;

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();   // keep Java's "interrupted" flag
        return null;
    } catch (Exception e) {
        // Never break the local evaluation because of LangSmith.
        log.warn("LangSmith upload failed, local results are fine: {}", e.getMessage());
        return null;
    }
}

// =================================================================== STEP 1

/**
 * Looks for a LangSmith dataset with our dataset's name.
 * Found -> reuse it. Not found -> create it.
 * So the dataset is created only the FIRST time; every later run reuses it,
 * and that is what lets LangSmith compare experiments on the same questions.
 *
 * NOTE: if you change the questions in evaluation-dataset.json, also change
 * its "name" (e.g. ...-v2), so LangSmith gets a fresh dataset.
 */
private String findOrCreateDataset(EvaluationDataset dataset) throws Exception {
    JsonNode found = send("GET", "/api/v1/datasets?name=" + enc(dataset.name()), null);
    if (found.isArray() && found.size() > 0) {
        return found.get(0).path("id").asText();
    }

    // "kv" = key-value dataset: inputs and outputs are simple JSON objects.
    JsonNode created = send("POST", "/api/v1/datasets", obj(
            "name", dataset.name(),
            "description", dataset.description(),
            "data_type", "kv"));
    log.info("created LangSmith dataset '{}'", dataset.name());
    return created.path("id").asText();
}

/**
 * Makes sure every one of our 12 cases exists as a LangSmith example.
 * Returns a map from OUR case id to LANGSMITH's example id, e.g.
 *   "return-window-standard" -> "6f1c...-..."
 * We need LangSmith's id to link each run to its example (step 3).
 */
private Map<String, String> uploadMissingExamples(String datasetId,
                                                  EvaluationDataset dataset) throws Exception {

    // 1. Read what is already in LangSmith. Each example carries our case id
    //    in its metadata, so we can match them up.
    Map<String, String> ids = new LinkedHashMap<>();
    JsonNode existing = send("GET",
            "/api/v1/examples?dataset=" + enc(datasetId) + "&limit=100", null);
    for (JsonNode example : existing) {
        String caseId = example.path("metadata").path("case_id").asText("");
        if (!caseId.isBlank()) {
            ids.put(caseId, example.path("id").asText());
        }
    }

    // 2. Upload only the cases that are missing (first run: all 12).
    for (EvaluationDataset.Case c : dataset.cases()) {
        if (ids.containsKey(c.id())) {
            continue;
        }
        JsonNode created = send("POST", "/api/v1/examples", obj(
                "dataset_id", datasetId,
                // inputs  = what goes INTO the app
                "inputs", obj("question", c.question()),
                // outputs = the CORRECT answer (the "reference")
                "outputs", obj(
                        "answer", c.expectedAnswer(),
                        "answerable", c.answerable(),
                        "relevant_source", c.relevantSource()),
                // metadata = extra labels; case_id is how we find it again later
                "metadata", obj("case_id", c.id(), "category", c.category())));
        ids.put(c.id(), created.path("id").asText());
    }
    return ids;
}

// =================================================================== STEP 2

/**
 * Creates one experiment for this run.
 * In LangSmith's API an experiment is a "session" (= project) that has a
 * reference_dataset_id. That link is what puts it under the dataset's
 * "Experiments" tab.
 * The metadata (topK, model, ...) appears as columns, so you can see which
 * setting each experiment used.
 */
private JsonNode createExperiment(String datasetId, int topK, Instant started,
                                  EvaluationRunner.Experiment summary) throws Exception {
    String name = "topK-" + topK + "-" + NAME_TIME.format(started);
    return send("POST", "/api/v1/sessions", obj(
            "name", name,
            "description", "RAG evaluation at topK " + topK + ", model " + modelName,
            "reference_dataset_id", datasetId,
            "start_time", started.toString(),
            "extra", obj("metadata", obj(
                    "top_k", topK,
                    "model", modelName,
                    "chunk_size_tokens", 150,
                    "similarity_threshold", 0.35,
                    "passed", summary.passed() + "/" + summary.cases()))));
}

// =================================================================== STEP 3

/**
 * Sends one run per case: the question, what our app answered, and which
 * example it belongs to (reference_example_id). That link lets LangSmith
 * show "expected vs actual" side by side.
 *
 * Times: the cases ran one after another, so we lay them out one after
 * another starting at 'started', each lasting as long as it really took.
 */
private List<String> createRuns(String experimentName, int topK, Instant started,
                                List<EvaluationRunner.CaseResult> details,
                                Map<String, String> exampleIds) throws Exception {

    List<String> runIds = new ArrayList<>();
    // LangSmith stores times in microseconds, so cut off the nanoseconds.
    Instant cursor = started.truncatedTo(ChronoUnit.MICROS);

    for (EvaluationRunner.CaseResult c : details) {
        String runId = UUID.randomUUID().toString();   // we choose the run's id ourselves
        Instant start = cursor;
        Instant end = start.plusMillis(c.scores().millis());
        cursor = end;

        send("POST", "/api/v1/runs", obj(
                "id", runId,
                // This run is a whole trace on its own, so trace_id = its own id,
                // and dotted_order = start time + id.
                "trace_id", runId,
                "dotted_order", DOTTED_ORDER_TIME.format(start) + runId,
                "name", "rag-pipeline",
                "run_type", "chain",
                "session_name", experimentName,            // which experiment
                "reference_example_id", exampleIds.get(c.id()), // which question
                "start_time", start.toString(),
                "end_time", end.toString(),
                "inputs", obj("question", c.question()),
                "outputs", obj(
                        "answer", c.actual(),
                        "sources", c.sources(),
                        "chunks_retrieved", c.chunksRetrieved()),
                "extra", obj("metadata", obj(
                        "case_id", c.id(),
                        "category", c.category(),
                        "top_k", topK))));

        runIds.add(runId);
    }
    return runIds;
}

// =================================================================== STEP 4

/**
 * Attaches our 4 scores (+ pass/fail) to one run as "feedback".
 * Each key becomes a column in LangSmith's experiment table, and LangSmith
 * averages each column for the whole experiment automatically.
 * Booleans are sent as 1 or 0 so they can be averaged too.
 */
private void attachScores(String runId, RagEvaluators.Scores s) throws Exception {
    feedback(runId, "correctness", s.correctness(), null);
    feedback(runId, "faithfulness", s.faithfulness(), null);
    feedback(runId, "relevance", s.relevance(), null);
    feedback(runId, "refusal_correct", s.refusalCorrect() ? 1 : 0, null);
    // The note ("Wrong answer." etc.) becomes a comment on the pass/fail score.
    feedback(runId, "passed", s.passed() ? 1 : 0, s.note());
}

/**
 * Sends one feedback score. Tries up to 3 times, 2 seconds apart, in case
 * LangSmith hasn't finished saving the run yet.
 */
private void feedback(String runId, String key, double score, String comment) throws Exception {
    Map<String, Object> body = obj("run_id", runId, "key", key, "score", score);
    if (comment != null && !comment.isBlank()) {
        body.put("comment", comment);
    }
    for (int attempt = 1; ; attempt++) {
        try {
            send("POST", "/api/v1/feedback", body);
            return;
        } catch (IllegalStateException e) {
            if (attempt >= 3) {
                throw e;
            }
            Thread.sleep(2000);
        }
    }
}

// ================================================================== helpers

/**
 * Sends one HTTP request to LangSmith and returns the JSON reply.
 *   method  GET / POST / PATCH
 *   path    e.g. /api/v1/datasets
 *   body    a Map turned into JSON, or null for no body
 * Every request carries the key in the "x-api-key" header (same header as tracing).
 * Any status 300 or higher is treated as an error.
 */
private JsonNode send(String method, String path, Object body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl + path))
            .timeout(Duration.ofSeconds(20))
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();

    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() >= 300) {
        // 401/403 almost always means the wrong kind of key (see top of file).
        throw new IllegalStateException(method + " " + path + " returned HTTP "
                + response.statusCode() + ": " + shorten(response.body()));
    }

    // Some calls reply with an empty body. Treat that as an empty JSON object.
    String text = response.body();
    return json.readTree(text == null || text.isBlank() ? "{}" : text);
}

/**
 * Small helper to build a JSON object as a Map:
 *   obj("name", "x", "top_k", 3)  ->  {"name":"x","top_k":3}
 * Why not Map.of(...)? Map.of() throws an error on null values, and some of
 * our values (like relevantSource) can be null.
 */
private static Map<String, Object> obj(Object... keyValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
        map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
}

// Makes text safe to put in a URL (spaces, slashes, etc.).
private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
}

// Keeps error messages short in the log.
private static String shorten(String text) {
    if (text == null) {
        return "";
    }
    return text.length() > 300 ? text.substring(0, 300) + "..." : text;
}
}