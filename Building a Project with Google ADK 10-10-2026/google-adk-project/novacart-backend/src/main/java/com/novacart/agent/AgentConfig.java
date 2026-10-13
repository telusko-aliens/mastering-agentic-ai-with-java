package com.novacart.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.ExitLoopTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.LongRunningFunctionTool;
import com.google.genai.types.GenerateContentConfig;
import com.novacart.ops.Guardrails;
import com.novacart.ops.TraceStore;
import com.novacart.tools.InventoryTools;
import com.novacart.tools.OrderTools;
import com.novacart.tools.ResolutionTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Every agent in NovaCart.
 *
 * <pre>
 *   copilot                 talks to the operator, and is the only one that can spend money
 *     ├── investigation     triage, then three parallel checks, then a plan
 *     ├── replacementFinder a loop: try a product, test it, try the next
 *     └── partnerWarehouse  another company, reached over A2A
 * </pre>
 *
 * <p>Note where {@code issueCompensation} lives: on the copilot itself, not behind an
 * {@code AgentTool}. An AgentTool runs its agent in a separate invocation, so a pending approval
 * would leave nothing in this session for a manager to answer tomorrow.
 */
@Configuration
public class AgentConfig {

    private final BaseLlm model;
    private final OrderTools orderTools;
    private final InventoryTools inventoryTools;
    private final ResolutionTools resolutionTools;
    private final Guardrails guardrails;
    private final TraceStore traces;

    public AgentConfig(BaseLlm model, OrderTools orderTools, InventoryTools inventoryTools,
                       ResolutionTools resolutionTools, Guardrails guardrails, TraceStore traces) {
        this.model = model;
        this.orderTools = orderTools;
        this.inventoryTools = inventoryTools;
        this.resolutionTools = resolutionTools;
        this.guardrails = guardrails;
        this.traces = traces;
    }

    // ==================================================================
    // 1. Investigation: triage, then three checks at once, then a plan
    // ==================================================================

    /**
     * The pipeline that runs on an incident, without anybody asking.
     *
     * <pre>
     *   TriageAgent          no model, no cost, writes issueType and severity to state
     *   Parallel             order-check, stock-check and impact-check, all at once
     *   partner-check        another company, over A2A, and only if we are short
     *   resolution-planner   reads all four, proposes one plan
     * </pre>
     *
     * <p>The three checks are parallel because none needs the others. The planner is a separate
     * step and not a fourth sibling: parallel agents all start before any of them has written
     * anything, so a sibling reading {@code {orderFindings}} would find it empty.
     */
    @Bean
    public SequentialAgent investigation(
            @Qualifier("partnerWarehouseAgent") BaseAgent partnerWarehouse) {

        LlmAgent orderCheck = traced(LlmAgent.builder()
                .name("order-check")
                .description("Establishes the facts of the order and its shipment")
                .instruction("""
                        Triage has already recorded this:
                          issue type : {issueType}
                          severity   : {severity}
                          order id   : {orderId?}

                        If there is an order id, call getOrder, then trackShipment if the order
                        has a shipment, then getCustomer.

                        Report only facts, in this shape, one per line:
                        Order:
                        Customer:
                        Shipment:
                        DaysLate:
                        Status:

                        Write "none" for anything you could not establish. Never guess a date.""")
                .model(model)
                .generateContentConfig(factual())
                .tools(
                        FunctionTool.create(orderTools, "getOrder"),
                        FunctionTool.create(orderTools, "trackShipment"),
                        FunctionTool.create(orderTools, "getCustomer"))
                .outputKey("orderFindings"));

        LlmAgent stockCheck = traced(LlmAgent.builder()
                .name("stock-check")
                .description("Establishes what NovaCart itself holds")
                .instruction("""
                        Triage recorded:
                          issue type : {issueType}
                          product     : {sku?}
                          order id    : {orderId?}

                        If you do not know the product code, call getOrder first to find it.

                        Then call checkStock to see where our units are, and checkCoverage to
                        find out whether they are enough. Do not work the shortfall out
                        yourself: checkCoverage returns it.

                        Report in this shape, copying the numbers exactly:
                        Product:
                        NovaCartStock:
                        UnitsNeeded:
                        ShortBy:
                        PartnerNeeded:""")
                .model(model)
                .generateContentConfig(factual())
                .tools(
                        FunctionTool.create(orderTools, "getOrder"),
                        FunctionTool.create(inventoryTools, "checkStock"),
                        FunctionTool.create(inventoryTools, "checkCoverage"))
                .outputKey("stockFindings"));

        LlmAgent impactCheck = traced(LlmAgent.builder()
                .name("impact-check")
                .description("Establishes how many other customers are affected")
                .instruction("""
                        Triage recorded:
                          product  : {sku?}
                          order id : {orderId?}

                        Find the product code if you only have an order id, then call
                        ordersAffectedBy.

                        Report in this shape:
                        AffectedOrders:
                        AffectedCustomers:
                        Notes:

                        If no product is involved, write "none" in all three.""")
                .model(model)
                .generateContentConfig(factual())
                .tools(
                        FunctionTool.create(orderTools, "getOrder"),
                        FunctionTool.create(orderTools, "ordersAffectedBy"))
                .outputKey("impactFindings"));

        ParallelAgent checks = ParallelAgent.builder()
                .name("checks")
                .description("Runs the order, stock and impact checks at the same time")
                .subAgents(orderCheck, stockCheck, impactCheck)
                .build();

        /*
         * The partner consult is its own step, after the parallel block, and that matters.
         * Inside the parallel block it hung forever: a RemoteA2AAgent bridges an HTTP client
         * into ADK's event stream, and ParallelAgent already runs its children on a bounded
         * scheduler. Moved out, with nothing else changed, the same run finished in 46 seconds.
         *
         * Rule: parallel is for local, independent work. Anything that leaves the process gets
         * its own sequential step, where it is also far easier to time out.
         */
        LlmAgent partnerCheck = traced(LlmAgent.builder()
                .name("partner-check")
                .description("Asks Partner Logistics whether they can cover what we cannot")
                .instruction("""
                        NovaCart's own stock position:
                        {stockFindings}

                        First call checkCoverage for the product to get the numbers yourself.
                        Do not decide from the text above: it is another agent's summary.

                        If partnerNeeded is false, reply with exactly one line:
                        PartnerNeeded: no

                        If partnerNeeded is true, ask the partner-warehouse agent how many units
                        they hold and how quickly they can dispatch to Mumbai. They are a
                        different company, so if they do not answer, say so and move on.

                        Then reply in this shape:
                        PartnerNeeded: yes
                        ShortBy:
                        PartnerStock:
                        LeadTimeDays:""")
                .model(model)
                .generateContentConfig(factual())
                .tools(
                        FunctionTool.create(inventoryTools, "checkCoverage"),
                        AgentTool.create(partnerWarehouse))
                .outputKey("partnerFindings"));

        LlmAgent planner = traced(LlmAgent.builder()
                .name("resolution-planner")
                .description("Turns the three findings into one recommended plan")
                .instruction("""
                        A case was triaged as {issueType} with severity {severity}.

                        Order findings:
                        {orderFindings}

                        Stock findings:
                        {stockFindings}

                        Partner findings:
                        {partnerFindings}

                        Impact findings:
                        {impactFindings}

                        Use calculateCompensation when the order is late, so the amount comes
                        from the rules rather than from you. Do not pay anything: you cannot,
                        and proposing is your job.

                        Reply in exactly this shape:
                        Diagnosis: <one line>
                        Plan:
                        1. <action>
                        2. <action>
                        Compensation: <amount in rupees, or none>
                        NeedsApproval: <yes or no>
                        CustomerMessage: <two lines the customer will actually be sent>

                        Every action must be one NovaCart can actually take:
                          - move stock between our own warehouses
                          - buy units from Partner Logistics, if PartnerNeeded is yes
                          - offer a replacement product
                          - pay compensation
                          - tell the customer something

                        Never propose a supplier delivery, a restock or a date that no tool
                        reported. If nothing above can fix it, say so plainly in the diagnosis.""")
                .model(model)
                .generateContentConfig(factual())
                .tools(FunctionTool.create(resolutionTools, "calculateCompensation"))
                .outputKey("resolutionPlan"));

        return SequentialAgent.builder()
                .name("investigation")
                .description("Investigates a delivery, stock or complaint case end to end and "
                        + "proposes a resolution. Use for anything that needs looking into.")
                .subAgents(new TriageAgent(), checks, partnerCheck, planner)
                .build();
    }

    // ==================================================================
    // 2. Replacement finder: a loop, because one try is rarely enough
    // ==================================================================

    /**
     * Try a replacement, test it, try the next one.
     *
     * <p>A loop and not a single call, because the first suggestion usually fails for a reason
     * the searcher could not know: out of stock, or too dear. The tester says why and the
     * searcher uses it. {@code maxIterations} is the seatbelt.
     */
    @Bean
    public LoopAgent replacementFinder() {

        LlmAgent searcher = traced(LlmAgent.builder()
                .name("replacement-searcher")
                .description("Proposes one replacement product")
                .instruction("""
                        The customer ordered a product that cannot be supplied.

                        Why the last suggestion was rejected, if there was one:
                        {rejection?}

                        Call findAlternatives, then propose exactly ONE product that has not
                        already been rejected.

                        Reply with one line only:
                        Candidate: <SKU> <name>""")
                .model(model)
                .generateContentConfig(factual())
                .tools(FunctionTool.create(inventoryTools, "findAlternatives"))
                .outputKey("candidate"));

        LlmAgent tester = traced(LlmAgent.builder()
                .name("replacement-tester")
                .description("Accepts or rejects the proposed replacement")
                .instruction("""
                        Proposed replacement:
                        {candidate}

                        Call evaluateReplacement with the original product, this candidate and
                        the warehouse.

                        If the tool says suitable is true, call the exit_loop tool and reply
                        with one line: ACCEPTED <SKU> <name> <price>.

                        If it is false, do NOT call the tool. Reply with one line saying why,
                        so the searcher can try something else.""")
                .model(model)
                .generateContentConfig(factual())
                .tools(
                        FunctionTool.create(inventoryTools, "evaluateReplacement"),
                        // The tool the model sees as exit_loop. Name it that way in the
                        // instruction or it will never be called.
                        ExitLoopTool.INSTANCE)
                .outputKey("rejection"));

        return LoopAgent.builder()
                .name("replacement-finder")
                .description("Finds a replacement product that is actually in stock and not "
                        + "much dearer. Use when a product cannot be supplied.")
                .subAgents(searcher, tester)
                .maxIterations(3)
                .build();
    }

    // ==================================================================
    // 3. The copilot: the one an operator talks to
    // ==================================================================

    /**
     * The agent behind the chat box, and the only one that can spend money.
     *
     * <p>The pipelines are tools, not subAgents. With subAgents the investigation would take
     * over and the operator's next question would go to it instead of back here.
     */
    @Bean
    public LlmAgent copilot(SequentialAgent investigation, LoopAgent replacementFinder,
                            @Qualifier("partnerWarehouseAgent") BaseAgent partnerWarehouse) {

        return traced(LlmAgent.builder()
                .name("novacart-copilot")
                .description("NovaCart operations copilot")
                .instruction("""
                        You are the operations copilot at NovaCart, an online retailer. You work
                        with the operations team, not with customers directly.

                        For a quick factual question, use your own tools: getOrder,
                        trackShipment, getCustomer, checkStock, ordersAffectedBy.

                        For anything that needs looking into, a late delivery, a shortage, a
                        complaint, use the investigation tool. It triages, checks the order,
                        stock and impact at once, and comes back with a plan.

                        When a product cannot be supplied, use replacement-finder.

                        When NovaCart's own warehouses cannot cover something, ask
                        partner-warehouse. It is a different company, so treat a slow or missing
                        answer as a fact to report, not an error.

                        To act on a plan: transferStock to move stock, issueCompensation to pay
                        a customer, notifyCustomer to tell them, saveResolutionPlan to write the
                        case up.

                        Rules:
                        - Never invent an order, a stock number, a date or an amount.
                        - Compensation amounts come from calculateCompensation, never from you.
                        - Large compensation comes back as pending. Report the ticket number and
                          stop. Do not try to approve it.
                        - Keep answers under six lines unless you are reporting a plan.
                        - Plain text only. No markdown, no ** for bold, no bullet
                          characters. The screen shows exactly what you write.""")
                .model(model)
                .generateContentConfig(factual())

                .tools(
                        // Reads: cheap, safe, no approval needed.
                        FunctionTool.create(orderTools, "getOrder"),
                        FunctionTool.create(orderTools, "trackShipment"),
                        FunctionTool.create(orderTools, "getCustomer"),
                        FunctionTool.create(orderTools, "ordersAffectedBy"),
                        FunctionTool.create(inventoryTools, "checkStock"),
                        FunctionTool.create(resolutionTools, "calculateCompensation"),

                        // Writes: guarded, and one of them can stop for a human.
                        FunctionTool.create(resolutionTools, "transferStock"),
                        LongRunningFunctionTool.create(resolutionTools, "issueCompensation"),
                        FunctionTool.create(resolutionTools, "notifyCustomer"),
                        FunctionTool.create(resolutionTools, "saveResolutionPlan"),

                        // Whole agents, borrowed and handed back.
                        AgentTool.create(investigation),
                        AgentTool.create(replacementFinder),
                        AgentTool.create(partnerWarehouse)));
    }

    // ==================================================================
    // shared
    // ==================================================================

    /**
     * Puts the tracer and the guardrails on every agent, then builds it.
     *
     * <p>Callbacks are per-agent. Attach them to the copilot alone and the trace stops at the
     * first handover, which is where it was about to get useful.
     */
    private LlmAgent traced(LlmAgent.Builder builder) {
        return builder
                .beforeAgentCallback(traces.beforeAgent())
                .afterAgentCallback(traces.afterAgent())
                .beforeModelCallback(List.of(traces.beforeModel(), guardrails.blockInjection()))
                .afterModelCallback(List.of(traces.afterModel(), guardrails.redactEmails()))
                .beforeToolCallback(List.of(traces.beforeTool(), guardrails.capSpending()))
                .afterToolCallback(traces.afterTool())
                .build();
    }

    /**
     * Low temperature, because nothing here is a matter of taste.
     *
     * <p>maxOutputTokens is generous on purpose: it counts the model's internal thinking too. A
     * small budget returns an empty answer, not a short one, and that looks like a broken key.
     */
    private GenerateContentConfig factual() {
        return GenerateContentConfig.builder()
                .temperature(0.1f)
                .maxOutputTokens(2048)
                .build();
    }
}
