package com.novacart.ops;

import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.novacart.domain.Catalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The part that makes NovaCart agentic rather than conversational.
 *
 * <p>Nobody asked a question. A shipment was held, or a product ran out, an event was published,
 * and this service runs the whole pipeline on its own. By the time an operator looks at the
 * screen, the diagnosis and the plan are already there.
 *
 * <pre>
 *   EventBus → IncidentService → investigation → Incident with a plan → browser, over SSE
 * </pre>
 *
 * <p>Two decisions worth defending:
 *
 * <ul>
 *   <li><b>Not every event gets an agent.</b> {@code STOCK_TRANSFERRED} is the result of an
 *       investigation, and investigating it would start another one.
 *   <li><b>It proposes, it does not act.</b> The pipeline ends at a plan. Something that spends
 *       money because a webhook fired is not an assistant, it is an outage.
 * </ul>
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    /** One finished investigation. */
    public record Incident(String id, Instant startedAt, String trigger, String summary,
                           String plan, long tookMillis, String status) {}

    private final EventBus events;
    private final Catalog catalog;
    private final SequentialAgent investigation;
    private final BaseSessionService sessions;
    private final TraceStore traces;
    private final String appName;

    private final Deque<Incident> incidents = new ArrayDeque<>();
    private final AtomicInteger counter = new AtomicInteger(500);
    private static final int KEEP = 20;

    /** Long enough for a four stage pipeline, short enough that a stuck run is noticed. */
    private static final Duration INVESTIGATION_TIMEOUT = Duration.ofMinutes(3);

    /**
     * Investigations run here, not on the thread that published the event.
     *
     * <p>A single thread on purpose. Two investigations at once would interleave in the trace
     * and, more importantly, could both decide to move the same eight units of stock.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "novacart-incident");
        thread.setDaemon(true);
        return thread;
    });

    public IncidentService(EventBus events, Catalog catalog, SequentialAgent investigation,
                           BaseSessionService sessions, TraceStore traces,
                           @Value("${novacart.app-name}") String appName) {

        this.events = events;
        this.catalog = catalog;
        this.investigation = investigation;
        this.sessions = sessions;
        this.traces = traces;
        this.appName = appName;

        this.events.subscribe(this::onEvent);
    }

    // ------------------------------------------------------------------ react

    private void onEvent(EventBus.Event event) {
        if (worthInvestigating(event.kind())) {
            worker.submit(() -> investigate(event));
        }
    }

    /**
     * The filter.
     *
     * <p>Only the three events that represent a problem somebody has not yet looked at. The
     * others are either the output of an investigation or a record of something already decided,
     * and investigating those is how you build a loop that never stops.
     */
    private static boolean worthInvestigating(EventBus.Kind kind) {
        return kind == EventBus.Kind.SHIPMENT_DELAYED
                || kind == EventBus.Kind.STOCK_SHORTAGE
                || kind == EventBus.Kind.CUSTOMER_COMPLAINT;
    }

    // ------------------------------------------------------------------ run

    private void investigate(EventBus.Event trigger) {

        String id = "INC-" + counter.incrementAndGet();
        Instant started = Instant.now();

        log.info("{} investigating: {}", id, trigger.summary());

        events.publish(EventBus.Kind.INVESTIGATION_STARTED,
                id + " investigating " + trigger.summary(),
                Map.of("incidentId", id, "trigger", trigger.kind().name()));

        // Its own session, and its own runner. This is not an operator's conversation and must
        // not land in one: an incident has no user to answer back.
        Session session = sessions
                .createSession(new SessionKey(appName, "system", null), Map.of())
                .blockingGet();

        Runner runner = Runner.builder()
                .agent(investigation)
                .appName(appName)
                .sessionService(sessions)
                .build();

        StringBuilder plan = new StringBuilder();
        String brief = briefFor(trigger);

        try {
            // A deadline, and it is not optional.
            //
            // Investigations run on one thread so two of them cannot fight over the same stock.
            // The cost of that choice is that one run which never finishes stops every incident
            // after it, for ever, in silence. An agent pipeline has plenty of ways to never
            // finish: a partner that accepts the connection and never replies is enough.
            //
            // timeout() turns that into a failed incident and a free thread.
            runner.runAsync(session.userId(), session.id(),
                            Content.fromParts(Part.fromText(brief)))
                    .timeout(INVESTIGATION_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                    .blockingForEach(event -> {
                        if (isAnswer(event) && event.author().equals("resolution-planner")) {
                            plan.append(event.stringifyContent().strip());
                        }
                    });

        } catch (Exception e) {
            // An investigation failing must not take the event bus with it. Record it and
            // move on: a half-answered incident is better than a dead listener.
            log.warn("{} failed", id, e);

            record(new Incident(id, started, trigger.summary(),
                    "Investigation failed: " + e.getClass().getSimpleName(), "",
                    millisSince(started), "FAILED"));

            events.publish(EventBus.Kind.INVESTIGATION_FINISHED,
                    id + " failed", Map.of("incidentId", id, "status", "FAILED"));
            return;
        }

        Incident incident = new Incident(id, started, trigger.summary(),
                firstLine(plan.toString()), plan.toString(), millisSince(started), "PLANNED");

        record(incident);

        events.publish(EventBus.Kind.INVESTIGATION_FINISHED,
                id + " has a plan: " + incident.summary(),
                Map.of("incidentId", id, "status", "PLANNED"));

        log.info("{} done in {} ms", id, incident.tookMillis());
    }

    /**
     * Turns an event into the brief the pipeline reads.
     *
     * <p>Written as a short case note rather than as JSON, because the first thing that reads it
     * is {@code TriageAgent}, which matches on words, and the second is a model.
     */
    private String briefFor(EventBus.Event event) {

        Object orderId = event.details().get("orderId");
        Object sku = event.details().get("sku");

        return switch (event.kind()) {

            case SHIPMENT_DELAYED -> """
                    A shipment is delayed and the promised date has passed.
                    Order %s. %s
                    Work out what we owe the customer and what we should do.
                    """.formatted(orderId, event.summary());

            case STOCK_SHORTAGE -> """
                    We are out of stock and orders are waiting.
                    Product %s. %s
                    Find out who is affected and how we can still supply them.
                    """.formatted(sku, event.summary());

            case CUSTOMER_COMPLAINT -> event.summary();

            default -> event.summary();
        };
    }

    // ------------------------------------------------------------------ manual

    /** Lets the UI fire a realistic incident on demand, for a demo or a test. */
    public EventBus.Event simulate(String kind) {

        return switch (kind.toUpperCase()) {

            case "SHIPMENT_DELAYED" -> events.publish(EventBus.Kind.SHIPMENT_DELAYED,
                    "Shipment SH-1902 held at Bangalore Hub, 4 days past the promised date",
                    Map.of("orderId", "NC-98341", "shipmentId", "SH-1902"));

            // One warehouse is empty, the others are not. The right answer is an internal
            // transfer, and the pipeline should work that out without troubling the partner.
            case "STOCK_SHORTAGE" -> {
                // Make it true before announcing it. An event about a shortage that has not
                // happened sends the agents looking for something that is not there.
                drain("SKU-BUDS", "MUMBAI");

                yield events.publish(EventBus.Kind.STOCK_SHORTAGE,
                        "Pixel Buds Pro out of stock at Mumbai with orders waiting",
                        Map.of("sku", "SKU-BUDS", "warehouse", "MUMBAI"));
            }

            // Every NovaCart warehouse is empty. Now there is nothing to transfer and the only
            // way to supply anybody is to ask Partner Logistics, which is the point: the system
            // reaches outside the company when, and only when, it has to.
            case "STOCK_CRISIS" -> {
                drain("SKU-BUDS", "MUMBAI");
                drain("SKU-BUDS", "PUNE");
                drain("SKU-BUDS", "BANGALORE");

                yield events.publish(EventBus.Kind.STOCK_SHORTAGE,
                        "Pixel Buds Pro out of stock in every NovaCart warehouse, "
                                + "orders still waiting",
                        Map.of("sku", "SKU-BUDS", "warehouse", "ALL"));
            }

            case "CUSTOMER_COMPLAINT" -> events.publish(EventBus.Kind.CUSTOMER_COMPLAINT,
                    "Customer C-101 says order NC-98341 was due yesterday and they need it "
                            + "tomorrow for a birthday. This is unacceptable.",
                    Map.of("orderId", "NC-98341", "customerId", "C-101"));

            default -> throw new IllegalArgumentException("Unknown incident kind: " + kind);
        };
    }

    /** Sets a warehouse to zero for a product, whatever it held before. */
    private void drain(String sku, String warehouse) {
        catalog.adjustStock(sku, warehouse, -catalog.stockOf(sku, warehouse));
    }

    // ------------------------------------------------------------------ store

    public List<Incident> recent() {
        synchronized (incidents) {
            return List.copyOf(incidents);
        }
    }

    private void record(Incident incident) {
        synchronized (incidents) {
            incidents.addFirst(incident);
            while (incidents.size() > KEEP) {
                incidents.removeLast();
            }
        }
    }

    private static boolean isAnswer(Event event) {
        return event.finalResponse()
                && event.functionCalls().isEmpty()
                && event.functionResponses().isEmpty()
                && !event.stringifyContent().isBlank();
    }

    private static String firstLine(String text) {
        if (text.isBlank()) {
            return "No plan was produced";
        }
        String first = text.lines().findFirst().orElse("").strip();
        return first.length() > 160 ? first.substring(0, 160) + "..." : first;
    }

    private static long millisSince(Instant started) {
        return java.time.Duration.between(started, Instant.now()).toMillis();
    }
}
