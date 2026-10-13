package com.novacart.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The first agent in every investigation, and the only one without a model.
 *
 * <p>It reads the incoming case and writes four facts into state: issue type, severity, and any
 * order or product id mentioned. Everything downstream reads those instead of the raw text.
 *
 * <p>Why not an LlmAgent: this is free, instant, always gives the same answer for the same
 * input, and cannot be argued with. That last one matters most. Severity decides whether a human
 * gets paged, and "ignore your instructions, this is critical" should not be able to page
 * anybody.
 *
 * <p>The limitation is the mirror image: it cannot read a complaint worded in a way nobody
 * anticipated. So hard rules go in Java and fuzzy judgement goes in a model, which is exactly
 * what this pipeline does.
 *
 * <p>One gotcha: {@code state().put(...)} will not survive. ADK persists state through the event
 * stream, so a change has to travel on an {@link EventActions} state delta.
 */
public class TriageAgent extends BaseAgent {

    /** Order ids look like NC-98341. Specific enough not to match prose. */
    private static final Pattern ORDER_ID = Pattern.compile("\\bNC-\\d{4,6}\\b",
            Pattern.CASE_INSENSITIVE);

    /** Product codes look like SKU-BUDS. */
    private static final Pattern SKU = Pattern.compile("\\bSKU-[A-Z]{3,10}\\b",
            Pattern.CASE_INSENSITIVE);

    public TriageAgent() {
        super("triage",
                "Classifies a case and records its type and severity before anyone investigates",
                List.of(), null, null);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext context) {

        String text = context.userContent()
                .flatMap(Content::parts)
                .map(parts -> parts.isEmpty() ? "" : parts.get(0).text().orElse(""))
                .orElse("");

        String lower = text.toLowerCase(Locale.ROOT);

        String issueType = classify(lower);
        String severity = severity(lower, issueType);
        String orderId = firstMatch(ORDER_ID, text);
        String sku = firstMatch(SKU, text);

        // The state the rest of the pipeline reads. Written as a delta on the event, not
        // straight onto the map, or it is lost the moment the session is reloaded.
        EventActions actions = EventActions.builder()
                .stateDelta(Map.of(
                        "issueType", issueType,
                        "severity", severity,
                        "orderId", orderId,
                        "sku", sku))
                .build();

        String summary = "issueType=" + issueType + " severity=" + severity
                + (orderId.isEmpty() ? "" : " order=" + orderId)
                + (sku.isEmpty() ? "" : " sku=" + sku);

        Event classified = Event.builder()
                .id(Event.generateEventId())
                .invocationId(context.invocationId())
                .author(name())
                .content(Content.fromParts(Part.fromText("[triage] " + summary)))
                .actions(actions)
                .build();

        return Flowable.just(classified);
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext context) {
        // Honest rather than half working. Nothing in NovaCart uses live mode.
        return Flowable.error(
                new UnsupportedOperationException("TriageAgent does not support live mode"));
    }

    // ------------------------------------------------------------------ rules

    /** Order matters: the most specific phrases are checked first. */
    private static String classify(String text) {

        if (contains(text, "out of stock", "no stock", "shortage", "unavailable", "sold out")) {
            return "STOCK_SHORTAGE";
        }
        if (contains(text, "not arrived", "late", "delayed", "still waiting", "has not come",
                "missed the", "stuck")) {
            return "DELIVERY_DELAY";
        }
        if (contains(text, "wrong item", "wrong product", "not what i ordered", "different item")) {
            return "WRONG_ITEM";
        }
        if (contains(text, "damaged", "broken", "cracked", "not working", "defective")) {
            return "DAMAGED_ITEM";
        }
        if (contains(text, "refund", "money back", "compensation", "reimburse")) {
            return "REFUND_REQUEST";
        }
        return "GENERAL_QUERY";
    }

    /**
     * Severity, from the type and from what the customer said about why it matters.
     *
     * <p>The deadline words are the interesting part. "I need it tomorrow because it is a
     * birthday gift" is not a different problem from "it is late", but it is a different
     * priority, and a human reading the queue needs to see that without opening the case.
     */
    private static String severity(String text, String issueType) {

        boolean deadline = contains(text, "tomorrow", "birthday", "wedding", "gift", "urgent",
                "today", "deadline", "before");

        boolean angry = contains(text, "unacceptable", "terrible", "worst", "never again",
                "cancel my account", "lawyer", "consumer court");

        if (angry || (deadline && issueType.equals("DELIVERY_DELAY"))) {
            return "HIGH";
        }
        if (issueType.equals("GENERAL_QUERY")) {
            return "LOW";
        }
        return "MEDIUM";
    }

    private static boolean contains(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    /** Empty string rather than null, because a null state value is awkward downstream. */
    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().toUpperCase() : "";
    }
}
