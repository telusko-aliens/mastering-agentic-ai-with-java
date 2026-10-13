package com.novacart.web;

import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.novacart.domain.Catalog;
import com.novacart.ops.ApprovalQueue;
import com.novacart.ops.EventBus;
import com.novacart.ops.IncidentService;
import com.novacart.ops.TraceStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole API, in one controller.
 *
 * <p>It replaces {@code AdkWebServer}, which Google's own docs say is not for production: no
 * authentication, no rate limiting, and anybody who reaches the port can spend your model
 * budget.
 *
 * <pre>
 *   POST /api/copilot               ask the copilot
 *   GET  /api/stream                live events, server-sent
 *   GET  /api/incidents             investigations the system ran on its own
 *   POST /api/incidents/simulate    fire a realistic incident
 *   GET  /api/approvals             money waiting for a manager
 *   POST /api/approvals/{ticket}    decide, and resume the conversation
 *   GET  /api/traces                what the agents actually did
 *   GET  /api/catalog               orders, products and stock
 * </pre>
 *
 * <p>No security here, because the class is about ADK. In production the first thing to add is
 * authentication and a role check on the approval endpoint.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "${novacart.cors.allowed-origin}")
public class OpsController {

    private static final Logger log = LoggerFactory.getLogger(OpsController.class);

    public record CopilotRequest(
            @NotBlank String operator,
            String sessionId,
            @NotBlank @Size(max = 2000) String message) {}

    public record CopilotReply(String sessionId, String reply, List<String> toolsUsed,
                               String pendingTicket) {}

    private final Runner runner;
    private final BaseSessionService sessions;
    private final ApprovalQueue approvals;
    private final IncidentService incidents;
    private final EventBus events;
    private final TraceStore traces;
    private final Catalog catalog;
    private final String appName;

    /**
     * SSE connections live on their own pool.
     *
     * <p>An emitter holds its thread for as long as the browser is open, which is forever. Doing
     * that on Tomcat's own threads is how a service with ten operators stops answering anybody.
     */
    private final ExecutorService streamPool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "novacart-sse");
        thread.setDaemon(true);
        return thread;
    });

    public OpsController(Runner runner, BaseSessionService sessions, ApprovalQueue approvals,
                         IncidentService incidents, EventBus events, TraceStore traces,
                         Catalog catalog, @Value("${novacart.app-name}") String appName) {
        this.runner = runner;
        this.sessions = sessions;
        this.approvals = approvals;
        this.incidents = incidents;
        this.events = events;
        this.traces = traces;
        this.catalog = catalog;
        this.appName = appName;
    }

    // ------------------------------------------------------------------ copilot

    @PostMapping("/copilot")
    public CopilotReply copilot(@Valid @RequestBody CopilotRequest request) {

        Session session = findOrCreate(request.operator(), request.sessionId());

        List<String> toolsUsed = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        String[] pendingTicket = {null};

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(request.message())))

                .blockingForEach(event -> {

                    event.functionCalls().forEach(call -> toolsUsed.add(call.name().orElse("?")));

                    capturePending(event, session, pendingTicket);

                    if (isAnswer(event)) {
                        answer.append(event.stringifyContent().strip());
                    }
                });

        return new CopilotReply(session.id(), answer.toString(), toolsUsed, pendingTicket[0]);
    }

    // ------------------------------------------------------------------ live

    /**
     * The event stream the dashboard watches.
     *
     * <p>This is what lets the browser show an investigation that nobody asked for. The agents
     * run on the server because a shipment was held; the browser finds out because of this.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {

        SseEmitter emitter = new SseEmitter(0L);

        Runnable unsubscribe = events.subscribe(event -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("ops")
                        .data(Map.of(
                                "id", event.id(),
                                "at", event.at().toString(),
                                "kind", event.kind().name(),
                                "summary", event.summary(),
                                "details", event.details())));

            } catch (IOException | IllegalStateException e) {
                // The browser closed the tab. Expected, and not worth a stack trace.
                emitter.complete();
            }
        });

        // Unsubscribe on every ending, or a listener is leaked per page refresh and the bus
        // slowly fills with dead browsers.
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(error -> unsubscribe.run());

        streamPool.submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("hello").data("connected"));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @GetMapping("/events")
    public List<EventBus.Event> recentEvents() {
        return events.recent();
    }

    // ------------------------------------------------------------------ incidents

    @GetMapping("/incidents")
    public List<IncidentService.Incident> incidents() {
        return incidents.recent();
    }

    @PostMapping("/incidents/simulate")
    public ResponseEntity<?> simulate(@RequestParam String kind) {
        try {
            // Returns as soon as the event is published. The investigation runs on its own
            // thread and the browser learns about it through the stream, which is the whole
            // point of the design.
            return ResponseEntity.ok(incidents.simulate(kind));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ approvals

    @GetMapping("/approvals")
    public Map<String, Object> approvals() {
        return Map.of("pending", approvals.pending(), "all", approvals.all());
    }

    /**
     * A manager decides, and the operator's conversation carries on by itself.
     *
     * <p>The decision is not sent to the agent as a sentence. It goes back as a
     * {@link FunctionResponse} carrying <b>the same id</b> as the original tool call, which is
     * what tells the model which question has just been answered. Plain text, or a new id,
     * leaves a tool call that was never resolved.
     *
     * <p>Finding that id means reading back through the stored conversation, which only works
     * because sessions are in a database.
     */
    @PostMapping("/approvals/{ticket}")
    public ResponseEntity<?> decide(@PathVariable String ticket,
                                    @RequestParam boolean approved,
                                    @RequestParam(defaultValue = "manager") String by,
                                    @RequestParam(required = false) String note) {

        ApprovalQueue.Request request = approvals.get(ticket);

        if (request == null) {
            return ResponseEntity.notFound().build();
        }

        if (!"PENDING".equals(request.status())) {
            return ResponseEntity.ok(Map.of("alreadyDecided", true, "request", request));
        }

        ApprovalQueue.Request decided = approvals.decide(ticket, approved, by, note);
        String operatorWasTold = resume(request, approved, note);

        log.info("{} {} by {}", ticket, approved ? "approved" : "rejected", by);

        return ResponseEntity.ok(Map.of("request", decided, "operatorWasTold", operatorWasTold));
    }

    private String resume(ApprovalQueue.Request request, boolean approved, String note) {

        if (request.sessionId() == null) {
            return "";
        }

        Session session = sessions
                .getSession(appName, operatorOf(request), request.sessionId(), Optional.empty())
                .blockingGet();

        if (session == null) {
            return "";
        }

        // Walk backwards for the most recent unanswered compensation call, which is the right
        // one when an operator has asked twice.
        String callId = null;

        for (int i = session.events().size() - 1; i >= 0 && callId == null; i--) {
            for (var call : session.events().get(i).functionCalls()) {
                if (call.name().orElse("").equals("issueCompensation")) {
                    callId = call.id().orElse(null);
                    break;
                }
            }
        }

        if (callId == null) {
            return "";
        }

        Content result = Content.fromParts(Part.builder()
                .functionResponse(FunctionResponse.builder()
                        .id(callId)
                        .name("issueCompensation")
                        .response(approved
                                ? Map.of("status", "APPROVED",
                                "message", "A manager approved the payment")
                                : Map.of("status", "REJECTED",
                                "message", note == null || note.isBlank()
                                        ? "A manager rejected the payment" : note))
                        .build())
                .build());

        StringBuilder reply = new StringBuilder();

        runner.runAsync(session.userId(), session.id(), result)
                .blockingForEach(event -> {
                    if (isAnswer(event)) {
                        reply.append(event.stringifyContent().strip());
                    }
                });

        return reply.toString();
    }

    /** The session belongs to whoever opened it, and that is the user id we stored. */
    private String operatorOf(ApprovalQueue.Request request) {
        return sessions.listSessions(appName, "ops@novacart.com").blockingGet().sessionIds()
                .contains(request.sessionId()) ? "ops@novacart.com" : "system";
    }

    // ------------------------------------------------------------------ traces

    @GetMapping("/traces")
    public List<TraceStore.Run> traces() {
        return traces.recent();
    }

    // ------------------------------------------------------------------ catalog

    @GetMapping("/catalog")
    public Map<String, Object> catalogSnapshot() {
        return Map.of(
                "orders", catalog.allOrders(),
                "products", catalog.allProducts(),
                "stock", catalog.allStock());
    }

    // ------------------------------------------------------------------ helpers

    private Session findOrCreate(String operator, String sessionId) {

        if (sessionId != null && !sessionId.isBlank()) {
            Session existing = sessions
                    .getSession(appName, operator, sessionId, Optional.empty())
                    .blockingGet();

            if (existing != null) {
                return existing;
            }
        }

        return sessions.createSession(new SessionKey(appName, operator, null), Map.of())
                .blockingGet();
    }

    /**
     * An answer is a text event, not a tool call and not a tool result.
     *
     * <p>Without this the API returns {@code FunctionCall{id=..., args=...}} as the reply. It is
     * the first thing that goes wrong when you wrap ADK in your own API.
     */
    private static boolean isAnswer(Event event) {
        return event.finalResponse()
                && event.functionCalls().isEmpty()
                && event.functionResponses().isEmpty()
                && !event.stringifyContent().isBlank();
    }

    private void capturePending(Event event, Session session, String[] ticketOut) {

        event.functionResponses().forEach(response -> {

            Map<String, Object> body = response.response().orElse(Map.of());

            if ("PENDING_APPROVAL".equals(body.get("status"))) {
                String ticket = String.valueOf(body.get("ticket"));
                approvals.attachSession(ticket, session.id());
                ticketOut[0] = ticket;
            }
        });
    }
}
