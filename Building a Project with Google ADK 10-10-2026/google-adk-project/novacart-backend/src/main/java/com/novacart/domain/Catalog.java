package com.novacart.domain;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The business data NovaCart runs on.
 *
 * <p>In-memory and seeded, so the project starts with one command and every demo begins from the
 * same place. Swap this bean for JPA repositories and nothing above it changes, because every
 * tool and every agent talks to this class and never to a database.
 *
 * <p>That boundary is deliberate and it is the first thing to point at in class. The agents in
 * this system have no SQL, no connection, no credentials. They can only call the methods below,
 * which means the limits of what an agent can do are the limits of this file.
 */
@Component
public class Catalog {

    // ------------------------------------------------------------------ types

    public record Customer(String id, String name, String email, String tier, String city) {}

    public record Product(String sku, String name, String category, int priceRupees) {}

    /** Stock of one product in one warehouse. */
    public record Stock(String sku, String warehouse, int units) {}

    public record OrderItem(String sku, String name, int quantity, int priceRupees) {}

    public record Order(String id, String customerId, List<OrderItem> items, int totalRupees,
                        LocalDate placedOn, String status, String shipmentId) {

        public Order withStatus(String newStatus) {
            return new Order(id, customerId, items, totalRupees, placedOn, newStatus, shipmentId);
        }
    }

    public record Shipment(String id, String orderId, String carrier, String trackingNumber,
                           String currentHub, LocalDate promisedBy, LocalDate estimatedBy,
                           String status, List<String> events) {}

    // ------------------------------------------------------------------ data

    private final Map<String, Customer> customers = new ConcurrentHashMap<>();
    private final Map<String, Product> products = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Shipment> shipments = new ConcurrentHashMap<>();

    /** Keyed "sku@warehouse", because stock is per product per place. */
    private final Map<String, Stock> stock = new ConcurrentHashMap<>();

    public Catalog() {
        seed();
    }

    // ------------------------------------------------------------------ reads

    public Optional<Customer> customer(String id) {
        return Optional.ofNullable(customers.get(upper(id)));
    }

    public Optional<Product> product(String sku) {
        return Optional.ofNullable(products.get(upper(sku)));
    }

    public Optional<Order> order(String id) {
        return Optional.ofNullable(orders.get(upper(id)));
    }

    public Optional<Shipment> shipment(String id) {
        return Optional.ofNullable(shipments.get(upper(id)));
    }

    public List<Order> ordersOf(String customerId) {
        return orders.values().stream()
                .filter(order -> order.customerId().equalsIgnoreCase(customerId))
                .sorted(Comparator.comparing(Order::placedOn).reversed())
                .toList();
    }

    /** Every order that is still open and contains this product. The blast radius of a shortage. */
    public List<Order> openOrdersContaining(String sku) {
        return orders.values().stream()
                .filter(order -> !order.status().equals("DELIVERED")
                        && !order.status().equals("CANCELLED"))
                .filter(order -> order.items().stream()
                        .anyMatch(item -> item.sku().equalsIgnoreCase(sku)))
                .sorted(Comparator.comparing(Order::placedOn))
                .toList();
    }

    public int stockOf(String sku, String warehouse) {
        Stock found = stock.get(upper(sku) + "@" + upper(warehouse));
        return found == null ? 0 : found.units();
    }

    public List<Stock> stockEverywhere(String sku) {
        return stock.values().stream()
                .filter(entry -> entry.sku().equalsIgnoreCase(sku))
                .sorted(Comparator.comparingInt(Stock::units).reversed())
                .toList();
    }

    /** Alternatives in the same category, cheapest first. Used by the replacement loop. */
    public List<Product> alternativesTo(String sku) {
        return product(sku)
                .map(original -> products.values().stream()
                        .filter(other -> !other.sku().equalsIgnoreCase(sku))
                        .filter(other -> other.category().equals(original.category()))
                        .sorted(Comparator.comparingInt(Product::priceRupees))
                        .toList())
                .orElse(List.of());
    }

    public List<Order> allOrders() {
        return orders.values().stream()
                .sorted(Comparator.comparing(Order::placedOn).reversed())
                .toList();
    }

    public List<Product> allProducts() {
        return List.copyOf(products.values());
    }

    public List<Stock> allStock() {
        return List.copyOf(stock.values());
    }

    // ------------------------------------------------------------------ writes

    /**
     * Moves units between warehouses.
     *
     * <p>Returns false rather than throwing when there is not enough, because the caller is a
     * tool and a tool that throws aborts the whole agent turn.
     */
    public synchronized boolean transferStock(String sku, String from, String to, int units) {

        if (units <= 0 || stockOf(sku, from) < units) {
            return false;
        }

        adjustStock(sku, from, -units);
        adjustStock(sku, to, units);
        return true;
    }

    public synchronized void adjustStock(String sku, String warehouse, int delta) {
        String key = upper(sku) + "@" + upper(warehouse);
        Stock current = stock.get(key);
        int units = Math.max(0, (current == null ? 0 : current.units()) + delta);
        stock.put(key, new Stock(upper(sku), upper(warehouse), units));
    }

    public synchronized void setOrderStatus(String orderId, String status) {
        orders.computeIfPresent(upper(orderId), (key, order) -> order.withStatus(status));
    }

    // ------------------------------------------------------------------ seed

    private void seed() {

        customers.put("C-101", new Customer("C-101", "Shramik", "shramik@novacart.com",
                "GOLD", "Mumbai"));
        customers.put("C-102", new Customer("C-102", "Navin", "navin@novacart.com",
                "SILVER", "Pune"));
        customers.put("C-103", new Customer("C-103", "Asha", "asha@novacart.com",
                "BRONZE", "Bangalore"));

        products.put("SKU-BUDS", new Product("SKU-BUDS", "Pixel Buds Pro",
                "AUDIO", 14999));
        products.put("SKU-ANC", new Product("SKU-ANC", "NovaSound ANC 300",
                "AUDIO", 11499));
        products.put("SKU-LITE", new Product("SKU-LITE", "NovaSound Lite",
                "AUDIO", 4999));
        products.put("SKU-LAPTOP", new Product("SKU-LAPTOP", "NovaBook 14",
                "COMPUTE", 68999));
        products.put("SKU-DOCK", new Product("SKU-DOCK", "NovaDock USB-C",
                "COMPUTE", 5499));

        // Mumbai is out of Pixel Buds. That one zero is what the whole shortage demo turns on.
        stockEntry("SKU-BUDS", "MUMBAI", 0);
        stockEntry("SKU-BUDS", "PUNE", 8);
        stockEntry("SKU-BUDS", "BANGALORE", 3);
        stockEntry("SKU-ANC", "MUMBAI", 42);
        stockEntry("SKU-ANC", "PUNE", 17);
        stockEntry("SKU-LITE", "MUMBAI", 120);
        stockEntry("SKU-LAPTOP", "MUMBAI", 6);
        stockEntry("SKU-LAPTOP", "PUNE", 0);
        stockEntry("SKU-DOCK", "MUMBAI", 55);

        LocalDate today = LocalDate.now();

        // A delayed order. The shipment is stuck and the promise date has passed.
        order("NC-98341", "C-101", today.minusDays(5), "SHIPPED", "SH-1902",
                item("SKU-BUDS", 1));
        shipment("SH-1902", "NC-98341", "BlueDart", "BD7741920", "Bangalore Hub",
                today.minusDays(1), today.plusDays(3), "DELAYED",
                List.of("Picked up at Mumbai", "In transit", "Arrived Bangalore Hub",
                        "Held: sorting backlog"));

        // Waiting on the stock that Mumbai does not have.
        order("NC-98355", "C-102", today.minusDays(2), "AWAITING_STOCK", null,
                item("SKU-BUDS", 2));

        order("NC-98360", "C-103", today.minusDays(2), "AWAITING_STOCK", null,
                item("SKU-BUDS", 1));

        // A large one, so the approval threshold has something to stop.
        order("NC-98377", "C-101", today.minusDays(9), "DELIVERED", "SH-1955",
                item("SKU-LAPTOP", 1));
        shipment("SH-1955", "NC-98377", "Delhivery", "DL5519023", "Delivered",
                today.minusDays(4), today.minusDays(4), "DELIVERED",
                List.of("Picked up at Mumbai", "Out for delivery", "Delivered"));

        order("NC-98390", "C-102", today.minusDays(1), "PACKED", null,
                item("SKU-ANC", 1), item("SKU-DOCK", 1));
    }

    private void stockEntry(String sku, String warehouse, int units) {
        stock.put(sku + "@" + warehouse, new Stock(sku, warehouse, units));
    }

    private OrderItem item(String sku, int quantity) {
        Product product = products.get(sku);
        return new OrderItem(sku, product.name(), quantity, product.priceRupees() * quantity);
    }

    private void order(String id, String customerId, LocalDate placedOn, String status,
                       String shipmentId, OrderItem... items) {

        List<OrderItem> lines = new ArrayList<>(List.of(items));
        int total = lines.stream().mapToInt(OrderItem::priceRupees).sum();

        orders.put(id, new Order(id, customerId, lines, total, placedOn, status, shipmentId));
    }

    private void shipment(String id, String orderId, String carrier, String tracking, String hub,
                          LocalDate promisedBy, LocalDate estimatedBy, String status,
                          List<String> events) {

        shipments.put(id, new Shipment(id, orderId, carrier, tracking, hub,
                promisedBy, estimatedBy, status, events));
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
