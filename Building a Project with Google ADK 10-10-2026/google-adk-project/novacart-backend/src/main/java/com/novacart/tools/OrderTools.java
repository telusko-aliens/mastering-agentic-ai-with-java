package com.novacart.tools;

import com.google.adk.tools.Annotations.Schema;
import com.novacart.domain.Catalog;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an agent can find out about orders, customers and shipments.
 *
 * <p>Read-only. Nothing in this class changes anything, which is why it needs no guardrail and
 * no approval: the worst an agent can do with these is look at the wrong order.
 *
 * <p>Writes live in {@link ResolutionTools}, behind a threshold and a human. Keeping reads and
 * writes in separate classes is not tidiness, it is how you can tell at a glance which tools
 * need protecting.
 */
@Component
public class OrderTools {

    private final Catalog catalog;

    public OrderTools(Catalog catalog) {
        this.catalog = catalog;
    }

    @Schema(description = "Look up one order: its items, total, status and the customer who "
            + "placed it. Use this before answering anything about a specific order.")
    public Map<String, Object> getOrder(
            @Schema(name = "orderId", description = "Order id, for example NC-98341")
            String orderId) {

        return catalog.order(orderId)
                .map(order -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("found", true);
                    result.put("orderId", order.id());
                    result.put("customerId", order.customerId());
                    result.put("placedOn", order.placedOn().toString());
                    result.put("status", order.status());
                    result.put("totalRupees", order.totalRupees());
                    result.put("items", order.items().stream()
                            .map(item -> Map.of("sku", item.sku(), "name", item.name(),
                                    "quantity", item.quantity()))
                            .toList());
                    result.put("shipmentId", order.shipmentId());
                    return result;
                })
                .orElse(Map.of("found", false, "orderId", orderId,
                        "message", "No order with that id"));
    }

    @Schema(description = "Look up a customer: their name, loyalty tier and city. The tier "
            + "decides what compensation they are entitled to.")
    public Map<String, Object> getCustomer(
            @Schema(name = "customerId", description = "Customer id, for example C-101")
            String customerId) {

        return catalog.customer(customerId)
                .map(customer -> Map.<String, Object>of(
                        "found", true,
                        "customerId", customer.id(),
                        "name", customer.name(),
                        "email", customer.email(),
                        "tier", customer.tier(),
                        "city", customer.city(),
                        "orderCount", catalog.ordersOf(customer.id()).size()))
                .orElse(Map.of("found", false, "customerId", customerId,
                        "message", "No customer with that id"));
    }

    @Schema(description = "Track a shipment: where it is, which carrier has it, when it was "
            + "promised and when it is now expected.")
    public Map<String, Object> trackShipment(
            @Schema(name = "shipmentId", description = "Shipment id, for example SH-1902")
            String shipmentId) {

        return catalog.shipment(shipmentId)
                .map(shipment -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("found", true);
                    result.put("shipmentId", shipment.id());
                    result.put("orderId", shipment.orderId());
                    result.put("carrier", shipment.carrier());
                    result.put("trackingNumber", shipment.trackingNumber());
                    result.put("currentHub", shipment.currentHub());
                    result.put("promisedBy", shipment.promisedBy().toString());
                    result.put("estimatedBy", shipment.estimatedBy().toString());
                    result.put("status", shipment.status());

                    // The number the resolution turns on, computed here rather than left for
                    // the model to work out from two dates. Models are bad at date arithmetic
                    // and this one decides whether a customer is owed anything.
                    long lateBy = java.time.temporal.ChronoUnit.DAYS.between(
                            shipment.promisedBy(), shipment.estimatedBy());
                    result.put("daysLate", Math.max(0, lateBy));

                    result.put("history", shipment.events());
                    return result;
                })
                .orElse(Map.of("found", false, "shipmentId", shipmentId,
                        "message", "No shipment with that id"));
    }

    @Schema(description = "List every open order that contains a product. Use this to find out "
            + "who is affected when a product runs out of stock.")
    public Map<String, Object> ordersAffectedBy(
            @Schema(name = "sku", description = "Product code, for example SKU-BUDS")
            String sku) {

        List<Catalog.Order> affected = catalog.openOrdersContaining(sku);

        return Map.of(
                "sku", sku.toUpperCase(),
                "count", affected.size(),
                "orders", affected.stream()
                        .map(order -> Map.of(
                                "orderId", order.id(),
                                "customerId", order.customerId(),
                                "status", order.status(),
                                "placedOn", order.placedOn().toString(),
                                "units", order.items().stream()
                                        .filter(item -> item.sku().equalsIgnoreCase(sku))
                                        .mapToInt(Catalog.OrderItem::quantity).sum()))
                        .toList());
    }
}
