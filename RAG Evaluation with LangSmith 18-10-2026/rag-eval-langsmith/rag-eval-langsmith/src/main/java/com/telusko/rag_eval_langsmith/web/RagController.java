package com.telusko.rag_eval_langsmith.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telusko.rag_eval_langsmith.service.IngestionService;
import com.telusko.rag_eval_langsmith.service.RagService;

@RestController
@RequestMapping("/api/rag")
public class RagController {
	 // Only these topK values are allowed. Keeping it to 2, 3, 5 means the
    // comparison in the Evaluation lesson is fair and easy to read.
    private static final int[] ALLOWED_TOP_K = {2, 3, 5};

    private final IngestionService ingestion;
    private final RagService rag;

    // Spring injects both services (constructor injection).
    public RagController(IngestionService ingestion, RagService rag) {
        this.ingestion = ingestion;
        this.rag = rag;
    }

    // The JSON body of /ask, e.g. {"question":"...","topK":3}
    // Spring converts the JSON into this record automatically.
    // topK is Integer (not int) so it can be null when the client doesn't send it.
    public record AskRequest(String question, Integer topK) {}

    /*
     * POST /api/rag/ingest?force=true
     *
     * Why write name = "force" explicitly?
     * Without it, Spring finds the parameter name using reflection, which only
     * works if Java compiled with the "-parameters" flag. Maven sets it, but
     * IntelliJ building on its own may not, and then you get the runtime error
     * "Name for argument of type [boolean] not specified". Writing the name
     * makes that error impossible.
     *
     * defaultValue = "false" -> if ?force is missing, treat it as false.
     */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        return ingestion.ingest(force);
    }

    // POST /api/rag/ask   body: {"question":"...","topK":3}
    // @RequestBody = read the JSON body into an AskRequest.
    // We clean up topK first, then hand off to RagService.
    // The returned RagService.Answer record is turned into JSON by Spring.
    @PostMapping("/ask")
    public RagService.Answer ask(@RequestBody AskRequest request) {
        return rag.ask(request.question(), validTopK(request.topK()));
    }

    // GET /api/rag/documents -> used by the Documents tab to show the policy text.
    // LinkedHashMap keeps the keys in the order we put them, so the JSON looks tidy.
    @GetMapping("/documents")
    public Map<String, Object> documents() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", IngestionService.SOURCE);
        out.put("chunksStored", ingestion.count());
        out.put("text", ingestion.policyText());
        return out;
    }

    // GET /api/rag/health -> "ready" if anything is stored, else "not ingested".
    // The green/orange badge on the Documents tab comes from this.
    @GetMapping("/health")
    public Map<String, Object> health() {
        int chunks = ingestion.count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", chunks > 0 ? "ready" : "not ingested");
        out.put("chunksStored", chunks);
        out.put("allowedTopK", ALLOWED_TOP_K);
        return out;
    }

    /**
     * Makes sure topK is 2, 3 or 5.
     * Missing or anything else (like 7 or -1) -> use 3. We don't throw an error,
     * we just fall back to a safe default.
     */
    private static int validTopK(Integer requested) {
        if (requested == null) {
            return 3;
        }
        for (int allowed : ALLOWED_TOP_K) {
            if (allowed == requested) {
                return requested;
            }
        }
        return 3;
    }

}
