package com.telusko.rag_eval_langsmith.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.telusko.rag_eval_langsmith.config.TracingConfig;

@RestController
@RequestMapping("/api/tracing")
public class TracingController
{
	// The TracingStatus bean created in TracingConfig (on/off, project, link).
    private final TracingConfig.TracingStatus status;

    public TracingController(TracingConfig.TracingStatus status) {
        this.status = status;
    }

    // GET /api/tracing/status
    // Returns JSON that the Tracing tab turns into the badge + span list.
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "enabled", status.enabled(),
                "project", status.project(),
                "dashboardUrl", status.dashboardUrl(),
                // A friendly message depending on on/off.
                "note", status.enabled()
                        ? "Traces are being sent. Open the project in LangSmith."
                        : "LANGSMITH_API_KEY is not set, so nothing is exported.",
                // A "cheat sheet" of the spans students will see in LangSmith.
                // This is just text for the UI - it doesn't create any spans.
                "spans", List.of(
                        // our span: the vector search
                        Map.of("name", "rag.retrieval",
                                "what", "the similarity search",
                                "tags", "question, top_k, documents_retrieved"),
                        // our span: the answer-writing step
                        Map.of("name", "rag.generation",
                                "what", "the model call that writes the answer",
                                "tags", "model, context_chunks, answer_length"),
                        // our span: one full evaluation run
                        Map.of("name", "rag.evaluation",
                                "what", "one whole run of the dataset",
                                "tags", "dataset, top_k, passed, correctness, faithfulness"),
                        // Spring AI's own span for the OpenAI call (we didn't write this one)
                        Map.of("name", "chat gpt-4.1-mini",
                                "what", "added by Spring AI itself, with tokens and timing",
                                "tags", "prompt and completion, from application.yml")));
    }

}
