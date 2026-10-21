package com.telusko.rag_eval_langsmith.config;


import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class TracingConfig
{

    private static final Logger log = LoggerFactory.getLogger(TracingConfig.class);

    // LangSmith's OTLP address (from their docs).
    // EU/APAC accounts use eu. / apac. / aws. prefixes on the host instead.
    private static final String LANGSMITH_OTLP = "https://api.smith.langchain.com/otel/v1/traces";

    // The LangSmith project name. Open this project on smith.langchain.com to see traces.
    public static final String PROJECT = "java-rag-evaluation-demo";

    /**
     * Creates the SpanExporter bean. Spring's tracing setup finds this bean
     * and uses it to ship every finished span.
     *
     * @Value("${LANGSMITH_API_KEY:}") reads the environment variable.
     * The ":" with nothing after it means "if missing, use an empty string"
     * (instead of crashing on startup).
     */
    @Bean
    public SpanExporter langSmithSpanExporter(
            @Value("${LANGSMITH_API_KEY:}") String apiKey) {

        // ---------------- CASE 1: no key -> tracing OFF, app still works ----------
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("LANGSMITH_API_KEY is not set. The app runs normally and sends no "
                    + "traces. Set it and restart to see traces in LangSmith.");

            // SpanExporter.composite() with nothing inside = an exporter that does nothing.
            // Why return a "do-nothing" exporter instead of nothing at all?
            // Because if there's no exporter bean, Spring Boot uses its default one,
            // which sends to localhost:4318, finds nothing there, and fills the log
            // with "Connection refused" for every single span.
            return SpanExporter.composite();
        }

        // ---------------- CASE 2: key present -> send traces to LangSmith --------
        log.info("exporting traces to LangSmith, project '{}'", PROJECT);

        return OtlpHttpSpanExporter.builder()
                .setEndpoint(LANGSMITH_OTLP)                // where to send
                .addHeader("x-api-key", apiKey)             // who we are
                .addHeader("Langsmith-Project", PROJECT)    // which project folder
                .setTimeout(Duration.ofSeconds(10))         // give up on a send after 10 s
                .build();
    }

    /**
     * A tiny bean that just remembers "is tracing on or off?".
     * The Tracing tab in the UI shows a green or grey badge from this,
     * instead of guessing.
     */
    @Bean
    public TracingStatus tracingStatus(@Value("${LANGSMITH_API_KEY:}") String apiKey) {
        return new TracingStatus(
                apiKey != null && !apiKey.isBlank(),   // enabled = we have a key
                PROJECT,
                "https://smith.langchain.com");
    }

    // A record holding the status: on/off, project name, dashboard link.
    public record TracingStatus(boolean enabled, String project, String dashboardUrl) {}
}

