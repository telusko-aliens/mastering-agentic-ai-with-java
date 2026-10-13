package com.partnerlogistics;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.Gemini;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Partner Logistics, a company NovaCart buys warehouse space from.
 *
 * <p>Run it on its own, before NovaCart:
 *
 * <pre>
 *   mvn compile exec:java
 * </pre>
 *
 * <p>It serves one agent over A2A on port 9100. NovaCart can ask it questions. What NovaCart
 * does not get, and this is the point, is the stock table: no connection string, no read
 * replica, no shared class.
 *
 * <p>It is a separate project rather than a package because that is the only case where A2A
 * earns its keep. Two agents in one JVM should be sub-agents: faster, typed, shared session. The
 * test is not "is this code far away" but "could I import it". Here the answer is no.
 *
 * <p>{@link A2AServer} implements the protocol in one file.
 */
public class WarehouseServer {

    private static final int PORT = 9100;

    /** Partner Logistics' own stock. NovaCart never sees this map. */
    private static final Map<String, Integer> STOCK = new ConcurrentHashMap<>(Map.of(
            "SKU-BUDS", 40,
            "SKU-ANC", 12,
            "SKU-LAPTOP", 0,
            "SKU-DOCK", 95));

    /** Where Partner Logistics can deliver from, and how fast. */
    private static final Map<String, Integer> LEAD_DAYS = Map.of(
            "MUMBAI", 1,
            "PUNE", 1,
            "BANGALORE", 2,
            "DELHI", 3);

    public static void main(String[] args) throws Exception {

        String apiKey = System.getenv("GOOGLE_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "GOOGLE_API_KEY is not set. Partner Logistics needs its own key: it is a "
                            + "different company and does not share NovaCart's.");
        }

        // Partner Logistics runs its own agent, with its own instruction and its own tools.
        // NovaCart has no say in any of it.
        LlmAgent agent = LlmAgent.builder()
                .name("partner-warehouse")
                .description("Answers questions about Partner Logistics stock and delivery times")
                .instruction("""
                        You are the warehouse desk at Partner Logistics.

                        Use checkPartnerStock for availability and quoteDispatch for how quickly
                        you can get something somewhere.

                        Answer in at most three lines, using only what the tools tell you.
                        Never guess a quantity or a date. If a tool says zero, say zero.
                        You do not discuss pricing, contracts or other clients.""")
                .model(Gemini.builder()
                        .modelName(System.getenv().getOrDefault("GEMINI_MODEL",
                                "gemini-flash-latest"))
                        .apiKey(apiKey)
                        .build())
                .tools(
                        FunctionTool.create(WarehouseServer.class, "checkPartnerStock"),
                        FunctionTool.create(WarehouseServer.class, "quoteDispatch"))
                .build();

        InMemoryRunner runner = new InMemoryRunner(agent, "partner-logistics");

        A2AServer server = new A2AServer(
                "partner-warehouse",
                "Checks stock and dispatch times at Partner Logistics warehouses",
                PORT,
                question -> ask(runner, question));

        System.out.println();
        System.out.println("Partner Logistics is open. NovaCart can ask, not query.");
        System.out.println("Agent card: " + server.url() + "/.well-known/agent-card.json");
        System.out.println("Ctrl+C to close.");

        // Keep the JVM alive. The HTTP server runs on its own threads.
        Thread.currentThread().join();
    }

    // ------------------------------------------------------------------ tools

    @Schema(description = "Check how many units of a product Partner Logistics holds.")
    public static Map<String, Object> checkPartnerStock(
            @Schema(name = "sku", description = "Product code, for example SKU-BUDS")
            String sku) {

        String key = sku == null ? "" : sku.trim().toUpperCase();

        if (!STOCK.containsKey(key)) {
            return Map.of("stocked", false, "sku", key,
                    "message", "Partner Logistics does not carry that product");
        }

        int units = STOCK.get(key);

        return Map.of("stocked", true, "sku", key, "units", units,
                "message", units == 0 ? "None in stock" : units + " units available");
    }

    @Schema(description = "Quote how quickly Partner Logistics can dispatch units of a product "
            + "to a city, and whether they have enough.")
    public static Map<String, Object> quoteDispatch(
            @Schema(name = "sku", description = "Product code") String sku,
            @Schema(name = "units", description = "How many units are needed") int units,
            @Schema(name = "toCity", description = "Destination city, for example MUMBAI")
            String toCity) {

        String key = sku == null ? "" : sku.trim().toUpperCase();
        String city = toCity == null ? "" : toCity.trim().toUpperCase();

        int available = STOCK.getOrDefault(key, 0);
        Integer leadDays = LEAD_DAYS.get(city);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sku", key);
        result.put("unitsRequested", units);
        result.put("city", city);

        if (leadDays == null) {
            result.put("canFulfil", false);
            result.put("reason", "Partner Logistics does not deliver to " + city);
            return result;
        }

        if (available < units) {
            result.put("canFulfil", false);
            result.put("unitsAvailable", available);
            result.put("reason", "Only " + available + " units in stock");
            return result;
        }

        result.put("canFulfil", true);
        result.put("unitsAvailable", available);
        result.put("leadTimeDays", leadDays);
        result.put("dispatchFee", units * 40);
        return result;
    }

    // ------------------------------------------------------------------ bridge

    /**
     * Runs the agent for one incoming A2A message and returns its answer as text.
     *
     * <p>A fresh session per request. NovaCart's conversation is NovaCart's; nothing about it
     * crosses the boundary, and nothing about this one goes back. All that moves between the two
     * companies is a question and an answer.
     */
    private static String ask(InMemoryRunner runner, String question) {

        Session session = runner.sessionService()
                .createSession("partner-logistics", "a2a-caller")
                .blockingGet();

        StringBuilder answer = new StringBuilder();

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(event -> {
                    if (event.finalResponse()
                            && event.functionCalls().isEmpty()
                            && event.functionResponses().isEmpty()) {
                        answer.append(event.stringifyContent().strip());
                    }
                });

        return answer.toString();
    }
}
