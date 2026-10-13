package com.novacart.ops;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * What happened, and who wants to know.
 *
 * <p>Things happen in a warehouse whether or not anybody is typing: a shipment is held, stock
 * runs out. Each one is published here and two listeners pick it up: the browser over
 * server-sent events, and {@link IncidentService}, which starts an investigation.
 *
 * <p>That second listener is the whole argument of this project. A chatbot waits to be asked. An
 * agent can be woken by something that happened.
 *
 * <p>An in-memory bus is enough for a class. In production this is Kafka or a queue.
 */
@Component
public class EventBus {

    /** The kinds of thing NovaCart reacts to. Named after what happened, not what to do. */
    public enum Kind {
        SHIPMENT_DELAYED,
        STOCK_SHORTAGE,
        CUSTOMER_COMPLAINT,
        STOCK_TRANSFERRED,
        COMPENSATION_PAID,
        CUSTOMER_NOTIFIED,
        INVESTIGATION_STARTED,
        INVESTIGATION_FINISHED,
        APPROVAL_REQUIRED,
        APPROVAL_DECIDED
    }

    public record Event(long id, Instant at, Kind kind, String summary,
                        Map<String, Object> details) {}

    private final AtomicLong nextId = new AtomicLong(1);

    /** Recent history, so a browser that connects late still has something to show. */
    private final Deque<Event> history = new ArrayDeque<>();
    private static final int KEEP = 200;

    /**
     * Who is listening.
     *
     * <p>CopyOnWriteArrayList because the read side is hot and the write side is rare: every
     * event walks the whole list, and a browser connecting or leaving is a handful of times an
     * hour. The usual advice about this class being slow does not apply to that shape.
     */
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

    public Event publish(Kind kind, String summary, Map<String, Object> details) {

        Event event = new Event(nextId.getAndIncrement(), Instant.now(), kind, summary,
                details == null ? Map.of() : details);

        synchronized (history) {
            history.addFirst(event);
            while (history.size() > KEEP) {
                history.removeLast();
            }
        }

        for (Consumer<Event> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // One broken listener, usually a browser that closed its tab, must not stop the
                // others or lose the event for everybody else.
            }
        }

        return event;
    }

    public Runnable subscribe(Consumer<Event> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public List<Event> recent() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }
}
