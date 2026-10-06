package com.telusko.util;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class TriageAgent extends BaseAgent
{
    private static final Pattern PNR = Pattern.compile(
            "\\b(?=[A-Z0-9]{6}\\b)(?=[A-Z0-9]*[A-Z])(?=[A-Z0-9]*[0-9])[A-Z0-9]{6}\\b");

    private final BaseAgent refundAgent;
    private final BaseAgent bookingAgent;
    private final BaseAgent generalAgent;

    public TriageAgent(BaseAgent refundAgent, BaseAgent bookingAgent, BaseAgent generalAgent) {

        // BaseAgent's constructor wants the sub-agents up front. They have to be declared here
        // even though we choose between them ourselves, because ADK builds the agent tree from
        // this list: parent links, name lookup and the web UI graph all read it.
        super("triage",
                "Routes a passenger message to the right specialist",
                List.of(refundAgent, bookingAgent, generalAgent),
                null,
                null);

        this.refundAgent = refundAgent;
        this.bookingAgent = bookingAgent;
        this.generalAgent = generalAgent;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext context) {

        String message = context.userContent()
                .map(Content::parts)
                .flatMap(parts -> parts.map(list -> list.isEmpty()
                        ? "" : list.get(0).text().orElse("")))
                .orElse("");

        BaseAgent chosen = route(message);
        String reason = reasonFor(message);

        // An event of our own, so the routing decision is visible in the transcript rather than
        // happening invisibly. In production this is what you would look at when somebody asks
        // why a message went to the wrong team.
        Event note = Event.builder()
                .id(Event.generateEventId())
                .invocationId(context.invocationId())
                .author(name())
                .content(Content.fromParts(Part.fromText(
                        "[triage] " + reason + " -> " + chosen.name())))
                .build();

        // Our event first, then everything the specialist produces. concat keeps the order,
        // which merge would not guarantee.
        return Flowable.concat(
                Flowable.just(note),
                chosen.runAsync(context));
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext context) {
        // Honest rather than silently broken. A half-working live mode is worse than none.
        return Flowable.error(
                new UnsupportedOperationException("TriageAgent does not support live mode"));
    }

    /** The rules. Plain Java, testable without a model. */
    private BaseAgent route(String message) {

        String text = message.toLowerCase(Locale.ROOT);

        if (text.contains("refund") || text.contains("cancel") || text.contains("money back")) {
            return refundAgent;
        }
        if (PNR.matcher(message).find()
                || text.contains("book") || text.contains("seat")) {
            return bookingAgent;
        }
        return generalAgent;
    }

    private String reasonFor(String message) {

        String text = message.toLowerCase(Locale.ROOT);

        if (text.contains("refund") || text.contains("cancel") || text.contains("money back")) {
            return "money words found";
        }
        if (PNR.matcher(message).find()) {
            return "looks like a PNR";
        }
        if (text.contains("book") || text.contains("seat")) {
            return "booking words found";
        }
        return "no rule matched";
    }
}
