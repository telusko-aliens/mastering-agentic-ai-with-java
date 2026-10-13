package com.novacart.tools;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Part;
import com.novacart.domain.Catalog;
import com.novacart.ops.ApprovalQueue;
import com.novacart.ops.EventBus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The tools that actually change something.
 *
 * <p>Everything in {@link OrderTools} and {@link InventoryTools} is a read. These are the writes,
 * and the difference matters: a wrong read wastes a few tokens, a wrong write moves real stock or
 * real money.
 *
 * <p>So every method here does one of three things:
 *
 * <ul>
 *   <li>applies a rule written in Java rather than in a prompt,
 *   <li>stops and asks a human when the amount is large,
 *   <li>or records what it did somewhere a person can audit later.
 * </ul>
 *
 * <p>The compensation ladder is the clearest example. A model asked to be generous will be
 * generous, and it will be generous differently each time. The table below is not negotiable and
 * does not appear in any instruction.
 */
@Component
public class ResolutionTools {

    /** Below this, the agent settles it. At or above, a human decides. */
    public static final int APPROVAL_THRESHOLD = 2_000;

    /** Nobody in this application can authorise more than this, approved or not. */
    public static final int HARD_CEILING = 25_000;

    private final Catalog catalog;
    private final ApprovalQueue approvals;
    private final EventBus events;

    public ResolutionTools(Catalog catalog, ApprovalQueue approvals, EventBus events) {
        this.catalog = catalog;
        this.approvals = approvals;
        this.events = events;
    }

    // ------------------------------------------------------------------ money

    /**
     * What the customer is owed, by the book.
     *
     * <p>A read, not a write, and deliberately separate from paying it. The planner needs to know
     * the number before it decides anything, and asking for the number should not cost money.
     */
    @Schema(description = "Work out what compensation a customer is entitled to for a late "
            + "delivery, from their loyalty tier and how late the order is. Does not pay it.")
    public Map<String, Object> calculateCompensation(
            @Schema(name = "orderId", description = "Order id, for example NC-98341")
            String orderId,

            @Schema(name = "daysLate", description = "How many days past the promised date")
            int daysLate) {

        Catalog.Order order = catalog.order(orderId).orElse(null);

        if (order == null) {
            return Map.of("found", false, "message", "No order with that id");
        }

        String tier = catalog.customer(order.customerId())
                .map(Catalog.Customer::tier)
                .orElse("BRONZE");

        // The ladder. Written once, applied the same way every time, and testable without a
        // model anywhere near it.
        int perDay = switch (tier) {
            case "GOLD" -> 300;
            case "SILVER" -> 200;
            default -> 100;
        };

        int amount = Math.min(perDay * Math.max(0, daysLate), order.totalRupees() / 2);

        return Map.of(
                "found", true,
                "orderId", order.id(),
                "customerTier", tier,
                "daysLate", daysLate,
                "compensationRupees", amount,
                "needsApproval", amount >= APPROVAL_THRESHOLD,
                "rule", tier + " customers receive " + perDay
                        + " rupees per day late, capped at half the order value");
    }

    /**
     * Pays it, or asks a human first.
     *
     * <p>Registered as a {@code LongRunningFunctionTool}, so a pending result is provisional and
     * the conversation can be resumed when the manager decides, possibly tomorrow.
     *
     * <p>Nothing here blocks. The request is written down, a ticket comes back, and the method
     * returns. That is what makes an approval that takes four hours survivable.
     */
    @Schema(description = "Issue compensation to a customer for an order. Amounts of 2000 "
            + "rupees or more are sent to a manager and come back as pending.")
    public Map<String, Object> issueCompensation(
            @Schema(name = "orderId", description = "Order id")
            String orderId,

            @Schema(name = "amountRupees", description = "Amount to pay in rupees")
            int amountRupees,

            @Schema(name = "reason", description = "Why, in one short sentence")
            String reason,

            ToolContext toolContext) {

        Catalog.Order order = catalog.order(orderId).orElse(null);

        if (order == null) {
            return Map.of("status", "FAILED", "message", "No order with that id");
        }

        // Checked here as well as in the guardrail. Not redundant: the guardrail is a policy
        // that might differ per deployment, this is what must hold whoever calls the method.
        if (amountRupees > HARD_CEILING) {
            return Map.of("status", "REFUSED",
                    "message", "Above the " + HARD_CEILING + " rupee ceiling for this system");
        }

        if (amountRupees < APPROVAL_THRESHOLD) {
            events.publish(EventBus.Kind.COMPENSATION_PAID,
                    "Paid " + amountRupees + " rupees on " + order.id(),
                    Map.of("orderId", order.id(), "amount", amountRupees));

            return Map.of("status", "PAID", "orderId", order.id(),
                    "amountRupees", amountRupees,
                    "message", "Within the automatic limit, paid immediately");
        }

        String ticket = approvals.submit(order.id(), order.customerId(), amountRupees,
                reason, toolContext.invocationId());

        return Map.of("status", "PENDING_APPROVAL", "ticket", ticket,
                "orderId", order.id(), "amountRupees", amountRupees,
                "message", "Sent to a manager for approval");
    }

    // ------------------------------------------------------------------ stock

    @Schema(description = "Move stock between NovaCart warehouses so an order can be fulfilled. "
            + "Only works for NovaCart's own warehouses.")
    public Map<String, Object> transferStock(
            @Schema(name = "sku", description = "Product code") String sku,
            @Schema(name = "fromWarehouse", description = "Where it is now") String fromWarehouse,
            @Schema(name = "toWarehouse", description = "Where it is needed") String toWarehouse,
            @Schema(name = "units", description = "How many units") int units) {

        boolean moved = catalog.transferStock(sku, fromWarehouse, toWarehouse, units);

        if (!moved) {
            return Map.of("status", "FAILED",
                    "message", "Not enough stock at " + fromWarehouse.toUpperCase()
                            + " to move " + units + " units");
        }

        events.publish(EventBus.Kind.STOCK_TRANSFERRED,
                units + " x " + sku.toUpperCase() + " moved "
                        + fromWarehouse.toUpperCase() + " to " + toWarehouse.toUpperCase(),
                Map.of("sku", sku.toUpperCase(), "units", units));

        return Map.of("status", "DONE", "sku", sku.toUpperCase(), "units", units,
                "from", fromWarehouse.toUpperCase(), "to", toWarehouse.toUpperCase());
    }

    // ------------------------------------------------------------------ record

    /**
     * Writes the resolution down as a file attached to the conversation.
     *
     * <p>An artifact rather than a return value, because this is a document a person reads, not
     * something the model needs to think about. The model gets the file name; whoever is handling
     * the case gets the file.
     */
    @Schema(description = "Save a written resolution plan as a file on this case, so a human "
            + "can read it later. Returns the file name, not the file.")
    public Map<String, Object> saveResolutionPlan(
            @Schema(name = "caseRef", description = "Order id or incident reference")
            String caseRef,

            @Schema(name = "plan", description = "The plan, in full")
            String plan,

            ToolContext toolContext) {

        String document = """
                NOVACART RESOLUTION PLAN
                ========================
                Case      : %s
                Prepared  : %s

                %s
                """.formatted(caseRef, java.time.LocalDateTime.now(), plan);

        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        String fileName = "resolution-" + caseRef.toUpperCase() + ".txt";

        toolContext.saveArtifact(fileName, Part.fromBytes(bytes, "text/plain")).blockingAwait();

        return Map.of("saved", true, "fileName", fileName, "sizeBytes", bytes.length);
    }

    @Schema(description = "Tell the customer what is happening. Use this once a resolution has "
            + "been decided, not before.")
    public Map<String, Object> notifyCustomer(
            @Schema(name = "customerId", description = "Customer id") String customerId,
            @Schema(name = "message", description = "What to tell them") String message) {

        String name = catalog.customer(customerId)
                .map(Catalog.Customer::name)
                .orElse(customerId);

        events.publish(EventBus.Kind.CUSTOMER_NOTIFIED,
                "Told " + name + ": " + message,
                Map.of("customerId", customerId.toUpperCase()));

        return Map.of("sent", true, "to", name);
    }
}
