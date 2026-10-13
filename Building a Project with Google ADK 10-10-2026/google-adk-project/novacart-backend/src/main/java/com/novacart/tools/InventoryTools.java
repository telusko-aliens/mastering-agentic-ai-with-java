package com.novacart.tools;

import com.google.adk.tools.Annotations.Schema;
import com.novacart.domain.Catalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Stock, and what to do when there is none.
 *
 * <p>Note what is missing: there is no tool here that asks the <b>partner warehouse</b> anything.
 * NovaCart runs three of its own warehouses and those are the ones below. The partner network is
 * a different company with a different database, and we reach it over A2A instead. See
 * {@code WarehouseAgent} in the a2a package.
 *
 * <p>That split is worth pointing at in class, because it is the honest answer to "when do I
 * actually need A2A". Not when the code is in another class. When it is in another company.
 */
@Component
public class InventoryTools {

    private final Catalog catalog;

    public InventoryTools(Catalog catalog) {
        this.catalog = catalog;
    }

    @Schema(description = "Check how many units of a product NovaCart holds, in every one of "
            + "its own warehouses. Does not cover partner warehouses.")
    public Map<String, Object> checkStock(
            @Schema(name = "sku", description = "Product code, for example SKU-BUDS")
            String sku) {

        List<Catalog.Stock> everywhere = catalog.stockEverywhere(sku);

        if (everywhere.isEmpty()) {
            return Map.of("found", false, "sku", sku.toUpperCase(),
                    "message", "We do not stock that product");
        }

        int total = everywhere.stream().mapToInt(Catalog.Stock::units).sum();

        return Map.of(
                "found", true,
                "sku", sku.toUpperCase(),
                "totalUnits", total,
                "byWarehouse", everywhere.stream()
                        .map(entry -> Map.of("warehouse", entry.warehouse(),
                                "units", entry.units()))
                        .toList());
    }

    /**
     * Can we cover the open orders for this product ourselves, and if not, by how much are we
     * short?
     *
     * <p>This exists because the first version did not have it, and the pipeline was worse for
     * it. {@code partner-check} used to read {@code stock-check}'s written report and decide
     * from the prose whether to involve the partner. It decided no while every warehouse was
     * empty, and the planner then invented a supplier delivery that nobody had promised.
     *
     * <p>The fix is the rule this project keeps coming back to: one agent should not have to
     * parse another agent's sentences to get a number that Java already knows. Counting units is
     * not a judgement call.
     */
    @Schema(description = "Work out whether NovaCart can cover its open orders for a product "
            + "from its own warehouses, and by how many units it is short if not.")
    public Map<String, Object> checkCoverage(
            @Schema(name = "sku", description = "Product code, for example SKU-BUDS")
            String sku) {

        int available = catalog.stockEverywhere(sku).stream()
                .mapToInt(Catalog.Stock::units)
                .sum();

        int needed = catalog.openOrdersContaining(sku).stream()
                .flatMap(order -> order.items().stream())
                .filter(item -> item.sku().equalsIgnoreCase(sku))
                .mapToInt(Catalog.OrderItem::quantity)
                .sum();

        int shortBy = Math.max(0, needed - available);

        return Map.of(
                "sku", sku.toUpperCase(),
                "unitsNeeded", needed,
                "unitsAvailable", available,
                "shortBy", shortBy,
                "canCoverInternally", shortBy == 0,
                "partnerNeeded", shortBy > 0);
    }

    @Schema(description = "Suggest products that could replace one that is out of stock. "
            + "Returns alternatives in the same category with their price and availability.")
    public Map<String, Object> findAlternatives(
            @Schema(name = "sku", description = "The product that is unavailable")
            String sku,

            @Schema(name = "warehouse", description = "Warehouse the replacement must ship from, "
                    + "for example MUMBAI")
            String warehouse) {

        List<Map<String, Object>> alternatives = catalog.alternativesTo(sku).stream()
                .map(product -> Map.<String, Object>of(
                        "sku", product.sku(),
                        "name", product.name(),
                        "priceRupees", product.priceRupees(),
                        "unitsAvailable", catalog.stockOf(product.sku(), warehouse)))
                .toList();

        return alternatives.isEmpty()
                ? Map.of("count", 0, "message", "No alternatives in that category")
                : Map.of("count", alternatives.size(), "alternatives", alternatives);
    }

    @Schema(description = "Compare a replacement product against the original: the price "
            + "difference, whether it is in stock, and whether it is an acceptable swap.")
    public Map<String, Object> evaluateReplacement(
            @Schema(name = "originalSku", description = "The product that is unavailable")
            String originalSku,

            @Schema(name = "candidateSku", description = "The product being considered instead")
            String candidateSku,

            @Schema(name = "warehouse", description = "Warehouse it must ship from")
            String warehouse) {

        Catalog.Product original = catalog.product(originalSku).orElse(null);
        Catalog.Product candidate = catalog.product(candidateSku).orElse(null);

        if (original == null || candidate == null) {
            return Map.of("suitable", false, "reason", "One of those products does not exist");
        }

        int units = catalog.stockOf(candidateSku, warehouse);

        if (units <= 0) {
            return Map.of("suitable", false, "candidateSku", candidate.sku(),
                    "reason", "Out of stock at " + warehouse.toUpperCase());
        }

        int difference = candidate.priceRupees() - original.priceRupees();

        // The rule is here, in Java, and not in the prompt. A model asked to decide what counts
        // as an acceptable downgrade will decide differently on Tuesday.
        boolean cheaperOrClose = difference <= original.priceRupees() * 0.1;

        return Map.of(
                "suitable", cheaperOrClose,
                "candidateSku", candidate.sku(),
                "candidateName", candidate.name(),
                "unitsAvailable", units,
                "priceDifferenceRupees", difference,
                "reason", cheaperOrClose
                        ? "In stock and no more than 10 percent dearer"
                        : "More than 10 percent dearer than the original");
    }
}
