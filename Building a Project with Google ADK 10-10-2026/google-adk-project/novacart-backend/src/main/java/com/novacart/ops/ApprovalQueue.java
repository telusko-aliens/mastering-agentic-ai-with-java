package com.novacart.ops;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Money the agent wants to spend, waiting for a person to say yes.
 *
 * <p>This is the human half of human-in-the-loop, and the thing to notice is what is <b>not</b>
 * here: no thread waiting, no lock held, no open connection. The agent wrote a row and finished
 * its turn. A manager may decide in ten seconds or tomorrow morning, and either is fine.
 *
 * <p>That property is what lets an approval survive a deployment, a restart or a shift change.
 * An implementation that blocks a thread cannot do any of those.
 */
@Component
public class ApprovalQueue {

    public record Request(
            String ticket,
            String orderId,
            String customerId,
            int amountRupees,
            String reason,
            String sessionId,
            String invocationId,
            Instant raisedAt,
            String status,
            String decidedBy,
            String note) {

        Request with(String newStatus, String by, String why) {
            return new Request(ticket, orderId, customerId, amountRupees, reason, sessionId,
                    invocationId, raisedAt, newStatus, by, why);
        }

        Request withSession(String newSessionId) {
            return new Request(ticket, orderId, customerId, amountRupees, reason, newSessionId,
                    invocationId, raisedAt, status, decidedBy, note);
        }
    }

    private final Map<String, Request> requests = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(1000);

    private final EventBus events;

    public ApprovalQueue(EventBus events) {
        this.events = events;
    }

    public String submit(String orderId, String customerId, int amountRupees, String reason,
                         String invocationId) {

        String ticket = "APV-" + counter.incrementAndGet();

        requests.put(ticket, new Request(ticket, orderId, customerId, amountRupees, reason,
                null, invocationId, Instant.now(), "PENDING", null, null));

        events.publish(EventBus.Kind.APPROVAL_REQUIRED,
                amountRupees + " rupees on " + orderId + " needs a manager",
                Map.of("ticket", ticket, "orderId", orderId, "amount", amountRupees));

        return ticket;
    }

    /**
     * Ties the request to the conversation that raised it.
     *
     * <p>Filled in afterwards because a tool only knows its invocation. The session id is known
     * to the controller, and without it there is nothing to resume when the manager decides.
     */
    public void attachSession(String ticket, String sessionId) {
        requests.computeIfPresent(ticket, (key, request) -> request.withSession(sessionId));
    }

    public Request decide(String ticket, boolean approved, String by, String note) {

        Request request = requests.get(ticket);

        if (request == null) {
            return null;
        }

        // Deciding twice must not pay twice. A manager refreshing the page and clicking again
        // is a normal thing to do.
        if (!"PENDING".equals(request.status())) {
            return request;
        }

        Request decided = request.with(approved ? "APPROVED" : "REJECTED", by,
                note == null ? "" : note);

        requests.put(ticket, decided);

        events.publish(EventBus.Kind.APPROVAL_DECIDED,
                ticket + " " + decided.status().toLowerCase() + " by " + by,
                Map.of("ticket", ticket, "approved", approved));

        return decided;
    }

    public Request get(String ticket) {
        return requests.get(ticket);
    }

    public List<Request> pending() {
        return requests.values().stream()
                .filter(request -> "PENDING".equals(request.status()))
                .sorted((a, b) -> a.raisedAt().compareTo(b.raisedAt()))
                .toList();
    }

    public List<Request> all() {
        return requests.values().stream()
                .sorted((a, b) -> b.raisedAt().compareTo(a.raisedAt()))
                .toList();
    }
}
