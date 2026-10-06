package com.telusko;

import com.google.adk.a2a.agent.RemoteA2AAgent;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.telusko.a2a.A2AServer;
import io.a2a.client.Client;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;

import java.io.IOException;
import java.util.List;

public class A2A
{
    private static final String APP_NAME = "telusko-adk";
    private static final int REMOTE_PORT = 9900;


    public static void main(String[] args) throws IOException {
        LlmAgent baggageBrain = LlmAgent.builder()
                .name("baggage-agent")
                .description("Knows the airline's baggage rules")
                .instruction("""
                        You are the baggage desk of an airline.

                        Cabin bag: 7 kg, one piece.
                        Checked bag: 15 kg on domestic, 25 kg on international.
                        Excess is 500 rupees per kilo.
                        Sports equipment needs 48 hours notice.

                        Answer in at most three lines, using only these rules.
                        """).model(ModelFactory.current()).build();

        InMemoryRunner remoteRunner =
                new InMemoryRunner(
                        baggageBrain,
                        "baggage-service"
                );

        A2AServer server = new A2AServer(
                "baggage-agent",

                "Answers questions about baggage allowance, "
                        + "excess fees and special items",
                REMOTE_PORT,
                question ->
                        askLocally(
                                remoteRunner,
                                question
                        )
        );

        try
        {
//             * GET /.well-known/agent-card.json
            //configure baggage agent url, agent card
            //Agent Card --> describe the remote agent
            AgentCard card = new AgentCard.Builder()
                    .protocolVersion("0.3.0")
                    .name("baggage-agent")
                    .description(
                            "Answers questions about baggage allowance, "
                                    + "excess fees and special items"
                    )
                    /*
                     * HTTP address of remote agent.
                     */
                    .url(server.url())
                    /*
                     * We are using JSON-RPC transport.
                     */
                    .preferredTransport("JSONRPC")
                    .version("1.0.0")
                    /*
                     * Streaming is disabled for this demo.
                     */
                    .capabilities(
                            new AgentCapabilities.Builder()
                                    .streaming(false)
                                    .build()
                    )
                    .defaultInputModes(
                            List.of("text")
                    )
                    .defaultOutputModes(
                            List.of("text")
                    )
                    /*
                     * Skills describe what this agent can do.
                     *
                     * Similar idea to tool descriptions.
                     *
                     * Good descriptions matter because agents
                     * use them for routing.
                     */
                    .skills(
                            List.of(
                                    new AgentSkill.Builder()
                                            .id("baggage")
                                            .name("Baggage rules")
                                            .description(
                                                    "Allowances, excess fees "
                                                            + "and special items"
                                            )
                                            .tags(
                                                    List.of("baggage")
                                            )
                                            .build()
                            )
                    )

                    .build();
                    //A2A Client

//           AgentCard describes remote agent and A2A client is used to communicate with remote agent
            // this A2A library is making the HTTP call internally
        Client a2aClient = Client.builder(card)
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder())
                .build();
            BaseAgent remoteBaggage =
                    RemoteA2AAgent.builder().name("baggage-agent")
                            .description(
                                    "Answers questions about baggage allowance, "
                                            + "excess fees and special items")
                            .agentCard(card)
                            /*
                             * This client performs the actual
                             * network communication.
                             */
                            .a2aClient(a2aClient)
                            .build();

            LlmAgent travel = LlmAgent.builder()
                    .name("travel-desk")
                    .description("Front desk for an airline")
                    .instruction("""
                            You are the front desk of an airline.

                            Baggage questions belong to the baggage agent.
                            Send them there.

                            Answer anything else yourself, in two lines.
                            """)
                    .model(ModelFactory.current())
                    /*
                     * Interesting part:
                     *
                     * remoteBaggage is physically accessed over HTTP,
                     * but from ADK's perspective it is a BaseAgent.
                     */
                    .subAgents(remoteBaggage)
                    .build();

            InMemoryRunner runner = new InMemoryRunner (travel, APP_NAME);
            ask(runner, "How much baggage can I take on a domestic flight?");
            ask(runner, "What time should I reach the airport?");

            runner.close().blockingAwait();

        } catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            // The server holds a thread pool and a port. Both outlive main without this.
            server.stop();
            remoteRunner.close().blockingAwait();

        }
    }
    private static String askLocally(InMemoryRunner runner, String question) {

        /*
         * Remote system creates its OWN session.
         */
        Session session =
                runner.sessionService()
                        .createSession(
                                "baggage-service",
                                "a2a-caller"
                        )
                        .blockingGet();


        StringBuilder answer =
                new StringBuilder();


        runner.runAsync(
                        session.userId(),
                        session.id(),
                        Content.fromParts(
                                Part.fromText(question)
                        )
                )

                .blockingForEach(event -> {

                    if (event.finalResponse()
                            && event.functionCalls().isEmpty()) {

                        answer.append(
                                event.stringifyContent()
                                        .strip()
                        );
                    }
                });


        return answer.toString();
    }




    private static void ask(InMemoryRunner runner, String question) {

        Session session = runner.sessionService()
                .createSession(APP_NAME, "student-1").blockingGet();
        System.out.println("\nyou: " + question);
        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(event -> {

                    event.functionCalls().stream()
                            .filter(call -> call.name().orElse("").equals("transfer_to_agent"))
                            .forEach(call -> System.out.println("  [transfer] -> "
                                    + call.args().orElse(java.util.Map.of()).get("agent_name")));

                    if (event.finalResponse()
                            && event.functionCalls().isEmpty()
                            && event.functionResponses().isEmpty()) {

                        String text = event.stringifyContent().strip().replace("\n", " ");
                        if (!text.isEmpty()) {
                            System.out.println("  " + event.author() + ": " + text);
                        }
                    }
                });
    }
}
