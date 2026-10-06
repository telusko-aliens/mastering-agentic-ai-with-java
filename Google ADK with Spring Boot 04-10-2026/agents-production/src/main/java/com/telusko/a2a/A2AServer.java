package com.telusko.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;

/**
 * A minimal A2A server, written on the JDK's own HTTP server.
 *
 * <p>There is a ready-made one: {@code google-adk-a2a-webservice} is a Spring Boot application
 * that does this properly. We write our own anyway, for one reason: the protocol is small enough
 * to read in a single file, and seeing it stops A2A feeling like magic.
 *
 * <h2>The whole protocol, for our purposes</h2>
 *
 * <pre>
 *   GET  /.well-known/agent-card.json    who am I, and what can I do
 *   POST /                               JSON-RPC, method "message/send"
 * </pre>
 *
 * <p>That is it. A2A is JSON-RPC over HTTP with an agreed envelope. No gRPC, no broker, no
 * registry. Two agents on two machines, or in two companies, agree on those shapes and they can
 * talk.
 *
 * <h2>Why this is not just a REST call</h2>
 *
 * It is a REST call, and the value is in the agreement rather than the transport. Because the
 * envelope is standard, an ADK agent can call a LangGraph agent, or a CrewAI one, or something
 * written by a team you have never met, without either side writing a client for the other.
 * The agent card is the contract.
 *
 * <p>Compare that with the sub-agents in class 3. Those live in your JVM, share your session and
 * are deployed with your code. A2A agents are separate programs with their own lifecycle,
 * owners and failure modes. Use sub-agents inside a system and A2A between them.
 */
public class A2AServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final String name;
    private final int port;

    /**
     * Starts the server immediately.
     *
     * @param handler what the remote agent actually does: text in, text out. Keeping it a
     *                plain Function means the transport here knows nothing about ADK, which is
     *                the right separation: this file is a protocol adapter, not an agent.
     */
    public A2AServer(String name, String description, int port, Function<String, String> handler)
            throws IOException {

        this.name = name;
        this.port = port;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);// create a basic HTTP Server

        // The agent card. A client reads this to learn the agent's name, what it is good at,
        // and where to send work. In our demo the client is handed the card directly in Java,
        // but a real one fetches this URL, so we serve it.
        // a client can discover about agent through
        server.createContext("/.well-known/agent-card.json", exchange -> {
            log("GET /.well-known/agent-card.json");
            respond(exchange, 200, agentCard(name, description, port).toString());
        });

        // Everything else is JSON-RPC on the root path.
        // A2A message arrives here
        server.createContext("/", exchange -> {

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{}");
                return;
            }
// reading incoming json body
            String body = read(exchange.getRequestBody());
            log("POST " + exchange.getRequestURI() + "  <- " + body);

            JsonNode request = JSON.readTree(body);
            String method = request.path("method").asText();
            JsonNode id = request.path("id");

            if (!"message/send".equals(method)) {
                // Everything we have not implemented fails honestly rather than silently.
                // -32601 is JSON-RPC for "method not found".
                respond(exchange, 200, error(id, -32601, "Unsupported method: " + method));
                return;
            }

            // Pull the text out of the incoming message. A message is a list of parts, and we
            // only handle text parts, which is enough for a class and is also most of the
            // traffic in practice.

            //extract text from a2a
            StringBuilder incoming = new StringBuilder();
            for (JsonNode part : request.path("params").path("message").path("parts")) {
                if (part.hasNonNull("text")) {
                    incoming.append(part.path("text").asText());
                }
            }

            String answer;
            try {
                answer = handler.apply(incoming.toString());
            } catch (Exception e) {
                // A remote agent that throws should still answer. A dead connection tells the
                // caller nothing, and the caller is another agent that cannot read a stack trace.
                answer = "The remote agent failed: " + e.getClass().getSimpleName();
            }

            // The caller's contextId ties several tasks to one conversation. Echo it back
            // when we get one so the other side can correlate.
            String contextId = request.path("params").path("message").path("contextId").asText(
                    UUID.randomUUID().toString());

            String response = taskResult(id, contextId, answer);
            log("     -> " + response);
            respond(exchange, 200, response);
        });

        server.start();
        System.out.println("[" + name + "] A2A server on http://localhost:" + port);
    }

    public void stop() {
        server.stop(0);
        System.out.println("[" + name + "] stopped");
    }

    public String url() {
        return "http://localhost:" + port;
    }

    // ------------------------------------------------------------------ json

    /**
     * The agent card.
     *
     * <p>Read the skills list as the public API of the agent. A calling agent decides whether to
     * send work here by reading these descriptions, exactly the way an LlmAgent picks a
     * sub-agent by its description. Vague skills get you the wrong work.
     */
    private static ObjectNode agentCard(String name, String description, int port) {

        ObjectNode card = JSON.createObjectNode();
        card.put("protocolVersion", "0.3.0");
        card.put("name", name);
        card.put("description", description);
        card.put("url", "http://localhost:" + port);
        card.put("preferredTransport", "JSONRPC");
        card.put("version", "1.0.0");

        ObjectNode capabilities = JSON.createObjectNode();
        capabilities.put("streaming", false);
        card.set("capabilities", capabilities);

        ArrayNode modes = JSON.createArrayNode().add("text");
        card.set("defaultInputModes", modes.deepCopy());
        card.set("defaultOutputModes", modes.deepCopy());

        ObjectNode skill = JSON.createObjectNode();
        skill.put("id", "default");
        skill.put("name", name);
        skill.put("description", description);
        skill.set("tags", JSON.createArrayNode().add("telusko"));
        card.set("skills", JSON.createArrayNode().add(skill));

        return card;
    }

    /**
     * A JSON-RPC success whose result is a completed A2A <b>task</b>.
     *
     * <p>The obvious thing to return is a bare message, and it almost works: the answer comes
     * back and the caller prints it. Then the program hangs forever on the next question.
     *
     * <p>A2A has two result shapes. A message is one turn of a chat. A task is a unit of work
     * with a lifecycle, and only a task carries a terminal state. ADK waits for that terminal
     * state before it closes the stream, so a message leaves it waiting for something that is
     * never coming. The warning it logs first, "Task ID or context ID is null", is the clue.
     *
     * <p>So: task, with {@code status.state = completed}, and the answer in an artifact.
     */
    private static String taskResult(JsonNode id, String contextId, String text) {

        ObjectNode part = JSON.createObjectNode();
        part.put("kind", "text");
        part.put("text", text);

        // The answer the caller reads. "artifact" here is A2A's word for a work product, not
        // the ADK artifact service from Step 6. Same word, unrelated feature.
        ObjectNode artifact = JSON.createObjectNode();
        artifact.put("artifactId", UUID.randomUUID().toString());
        artifact.put("name", "answer");
        artifact.set("parts", JSON.createArrayNode().add(part));

        ObjectNode status = JSON.createObjectNode();
        status.put("state", "completed");
        status.put("timestamp", OffsetDateTime.now().toString());

        ObjectNode task = JSON.createObjectNode();
        task.put("kind", "task");
        task.put("id", UUID.randomUUID().toString());
        task.put("contextId", contextId);
        task.set("status", status);
        task.set("artifacts", JSON.createArrayNode().add(artifact));
        task.set("history", JSON.createArrayNode());

        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", id);
        envelope.set("result", task);

        return envelope.toString();
    }

    private static String error(JsonNode id, int code, String message) {

        ObjectNode error = JSON.createObjectNode();
        error.put("code", code);
        error.put("message", message);

        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", id);
        envelope.set("error", error);

        return envelope.toString();
    }

    // ------------------------------------------------------------------ http

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void log(String line) {
        System.out.println("[" + name + "] " + line);
    }
}
