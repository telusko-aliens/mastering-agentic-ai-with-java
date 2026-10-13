package com.novacart.ops;

import com.google.adk.agents.Callbacks;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.novacart.tools.ResolutionTools;
import io.reactivex.rxjava3.core.Maybe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What NovaCart's agents are not allowed to do.
 *
 * <pre>
 *   injection     beforeModel   stops the question reaching the model   costs nothing
 *   spending      beforeTool    stops the money leaving                 costs one model call
 *   redaction     afterModel    stops the answer reaching the operator  costs the whole turn
 * </pre>
 *
 * <p>The middle one is the one that protects the business. The other two filter text, and text
 * can be argued with. A check in {@code beforeTool} means the method never runs, whatever the
 * model was talked into asking for.
 *
 * <p>Every guardrail logs when it fires. One that blocks silently is one you will not notice
 * blocking the wrong things.
 */
@Component
public class Guardrails {

    private static final Logger log = LoggerFactory.getLogger(Guardrails.class);

    /** Crude on purpose. A real one is a classifier, and the wiring below is identical. */
    private static final Pattern INJECTION = Pattern.compile(
            "(?i)(ignore (all )?(your |the )?(previous |above )?instructions"
                    + "|disregard (your|the) (rules|policy|instructions)"
                    + "|you are now|system prompt|reveal your (prompt|instructions)"
                    + "|approve (it|this) yourself)");

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    // ------------------------------------------------------------------ input

    public Callbacks.BeforeModelCallback blockInjection() {
        return (context, requestBuilder) -> {

            String question = lastUserText(requestBuilder.build().contents());

            if (!INJECTION.matcher(question).find()) {
                return Maybe.empty();
            }

            log.warn("guardrail: blocked suspected prompt injection, session {}",
                    context.sessionId());

            // Returning a response here means the model is never called. The block is free.
            return Maybe.just(LlmResponse.builder()
                    .content(Content.fromParts(Part.fromText(
                            "I can only act within NovaCart's operating rules. "
                                    + "Approvals are decided by a manager, not by me.")))
                    .build());
        };
    }

    // ------------------------------------------------------------------ action

    /**
     * The ceiling nobody can raise from a prompt.
     *
     * <p>Note this is a different number from the approval threshold. Between the two, a manager
     * decides. Above this, there is nothing to decide: this application does not do it.
     */
    public Callbacks.BeforeToolCallback capSpending() {
        return (invocation, tool, args, toolContext) -> {

            if (!tool.name().equals("issueCompensation")) {
                return Maybe.empty();
            }

            Object raw = args.get("amountRupees");
            int amount = raw instanceof Number number ? number.intValue() : 0;

            if (amount <= ResolutionTools.HARD_CEILING) {
                return Maybe.empty();
            }

            log.warn("guardrail: refused compensation of {} in session {}", amount,
                    invocation.session().id());

            // The tool does not run. Returning a map gives the model something to explain
            // rather than an exception that kills the turn.
            return Maybe.just(Map.of(
                    "status", "REFUSED",
                    "reason", "Compensation above " + ResolutionTools.HARD_CEILING
                            + " rupees cannot be issued by this system at all. "
                            + "Escalate to the regional manager."));
        };
    }

    // ------------------------------------------------------------------ output

    /**
     * Keeps customer email addresses out of operator-facing text.
     *
     * <p>The tools return them because the system needs them. The answer on a shared operations
     * screen does not, and a transcript is stored.
     */
    public Callbacks.AfterModelCallback redactEmails() {
        return (context, response) -> {

            String text = response.content()
                    .flatMap(Content::parts)
                    .map(parts -> parts.isEmpty() ? "" : parts.get(0).text().orElse(""))
                    .orElse("");

            if (text.isBlank() || !EMAIL.matcher(text).find()) {
                return Maybe.empty();
            }

            log.info("guardrail: redacted an email address, session {}", context.sessionId());

            String cleaned = EMAIL.matcher(text).replaceAll("[email hidden]");

            return Maybe.just(LlmResponse.builder()
                    .content(Content.fromParts(Part.fromText(cleaned)))
                    .build());
        };
    }

    // ------------------------------------------------------------------ helper

    private static String lastUserText(List<Content> contents) {

        for (int i = contents.size() - 1; i >= 0; i--) {
            Content content = contents.get(i);

            if (content.role().orElse("").equals("user")) {
                String text = content.parts()
                        .map(parts -> parts.isEmpty() ? "" : parts.get(0).text().orElse(""))
                        .orElse("");

                if (!text.isBlank()) {
                    return text.toLowerCase(Locale.ROOT);
                }
            }
        }

        return "";
    }
}
