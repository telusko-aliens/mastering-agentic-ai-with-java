package com.novacart.a2a;

import com.google.adk.a2a.agent.RemoteA2AAgent;
import com.google.adk.agents.BaseAgent;
import io.a2a.client.Client;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * NovaCart's connection to Partner Logistics.
 *
 * <p>Another company, another process, another repository. We have no database access and never
 * will. We have an address and an agent card, and that is enough to ask questions.
 *
 * <p>What comes back is a {@link BaseAgent} like any other, so it sits in a tool list next to
 * local tools and the model cannot tell which calls leave the building.
 *
 * <p>Two things that cost time the first time:
 *
 * <ul>
 *   <li>The card is not enough. {@code RemoteA2AAgent} also wants a built {@link Client}, and
 *       says so with "a2aClient cannot be null".
 *   <li>The partner must answer with a <b>task</b> in a terminal state, not a bare message. ADK
 *       waits for that state, so a message answers once and then hangs forever.
 * </ul>
 */
@Configuration
public class PartnerWarehouse {

    private static final Logger log = LoggerFactory.getLogger(PartnerWarehouse.class);

    @Bean
    public BaseAgent partnerWarehouseAgent(
            @Value("${novacart.partner.url}") String partnerUrl) {

        // In a real deployment this is fetched from
        // {partnerUrl}/.well-known/agent-card.json at startup, and cached. We build it here so
        // NovaCart still starts when the partner is down, which is the behaviour you want: a
        // partner outage should degrade one feature, not stop the whole application booting.
        AgentCard card = new AgentCard.Builder()
                .protocolVersion("0.3.0")
                .name("partner-warehouse")
                .description("Checks stock and dispatch times at Partner Logistics warehouses, "
                        + "which NovaCart does not own and cannot query directly")
                .url(partnerUrl)
                .preferredTransport("JSONRPC")
                .version("1.0.0")
                .capabilities(new AgentCapabilities.Builder().streaming(false).build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(new AgentSkill.Builder()
                        .id("partner-stock")
                        .name("Partner stock and dispatch")
                        .description("Availability and lead times at partner warehouses")
                        .tags(List.of("inventory", "logistics"))
                        .build()))
                .build();

        Client client;

        try {
            client = Client.builder(card)
                    .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder())
                    .build();

            log.info("partner warehouse agent wired to {}", partnerUrl);

        } catch (Exception e) {
            // Building the client does not call the partner, so this failing means something is
            // wrong with our own configuration. Worth a loud log rather than a silent null.
            throw new IllegalStateException(
                    "Could not build the A2A client for " + partnerUrl, e);
        }

        return RemoteA2AAgent.builder()
                .name("partner-warehouse")
                // This description is the routing table. The model picks this agent over the
                // local inventory tools because of the words "partner" and "not own".
                .description("Asks Partner Logistics, an external warehouse company, about "
                        + "stock they hold and how quickly they can dispatch it. Use this only "
                        + "when NovaCart's own warehouses cannot cover an order.")
                .agentCard(card)
                .a2aClient(client)
                .build();
    }
}
