package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.LongRunningFunctionTool;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.telusko.tool.ApprovalTools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

public class HumanInLoop {
    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args) {
        LlmAgent agent = LlmAgent.builder()
                .name("refund-agent")
                .description("Processes passenger refunds")
                .instruction("""
                        You process refunds for an airline.
                        
                        Use requestRefund with the PNR and the amount.
                        If it comes back PENDING_APPROVAL, tell the passenger it is with a
                        manager and give them the ticket number.
                        
                        Keep answers to two lines.""")
                .model(ModelFactory.current())

                // The one line that makes this human-in-the-loop. Same method, same signature.
                .tools(LongRunningFunctionTool.create(ApprovalTools.class, "requestRefund"))

                .build();

        InMemoryRunner runner = new InMemoryRunner(agent, APP_NAME);
        System.out.println(
                "########## small refund ##########"
        );


        Session small = runner.sessionService().createSession(
                APP_NAME,
                "student-1").blockingGet();

        runTurn(
                runner,
                small,
                "Please refund 1200 rupees on TL1001."
        );

        // CASE 2: LARGE REFUND
        // =========================================================

        System.out.println("\n--- LARGE REFUND ---");

        Session largeSession =
                runner.sessionService()
                        .createSession(APP_NAME, "student-1")
                        .blockingGet();


        /*
         * runTurn() sends this message to the agent.
         *
         * If human approval is required,
         * it returns the original FunctionCall.
         */
        FunctionCall pendingCall =
                runTurn(
                        runner,
                        largeSession,
                        "Please refund 48000 rupees on TL1002."
                );


        // No approval needed.
        if (pendingCall == null) {
            System.out.println("Nothing waiting for approval.");
            runner.close().blockingAwait();
            return;
        }


        // =========================================================
        // 3. HUMAN / MANAGER DECISION
        // =========================================================

        System.out.println("\n--- MANAGER APPROVAL ---");

        System.out.println(
                "Refund request: "
                        + pendingCall.args().orElse(Map.of())
        );

        System.out.print("Approve? y/n: ");

        Scanner scanner = new Scanner(System.in);

        String decision =
                scanner.hasNextLine()
                        ? scanner.nextLine().strip()
                        : "n";

        boolean approved =
                decision.equalsIgnoreCase("y");


        // =========================================================
        // 4. CREATE FUNCTION RESPONSE
        // =========================================================

        /*
         * Earlier the model created a FunctionCall.
         *
         * Now the human has made the decision.
         *
         * We send a FunctionResponse back using
         * the SAME function call ID.
         */

        Content humanDecision =
                Content.fromParts(

                        Part.builder()

                                .functionResponse(

                                        FunctionResponse.builder()

                                                // Same ID is very important.
                                                .id(
                                                        pendingCall.id()
                                                                .orElseThrow()
                                                )

                                                // Same tool/function name.
                                                .name(
                                                        pendingCall.name()
                                                                .orElseThrow()
                                                )

                                                // Human's decision.
                                                .response(
                                                        approved

                                                                ? Map.of(
                                                                "status", "APPROVED",
                                                                "message",
                                                                "Manager approved the refund"
                                                        )

                                                                : Map.of(
                                                                "status", "REJECTED",
                                                                "message",
                                                                "Manager rejected the refund"
                                                        )
                                                )

                                                .build()
                                )

                                .build()
                );


        // =========================================================
        // 5. RESUME SAME SESSION
        // =========================================================

        System.out.println("\n--- BACK TO AGENT ---");

        /*
         * Same session ID means:
         *
         * continue the previous conversation,
         * don't start a new one.
         */

        runner.runAsync(
                        largeSession.userId(),
                        largeSession.id(),
                        humanDecision
                )

                .blockingForEach(event -> {

                    // Print only normal final agent response.
                    if (event.finalResponse()
                            && event.functionCalls().isEmpty()
                            && event.functionResponses().isEmpty()) {

                        String text =
                                event.stringifyContent()
                                        .strip()
                                        .replace("\n", " ");

                        if (!text.isEmpty()) {
                            System.out.println("agent: " + text);
                        }
                    }
                });


        runner.close().blockingAwait();


    }


    // send that message to agent
    // look to agents / call result
    // print the agent response
    //if approval is pending, return the function call so we can approve it
    // otherise return null


    //    FunctionCall
//            id     = "abc123"
//    name   = "requestRefund"
//    args   = {
//    pnr: "TL1002",
//            amount: 48000
    // }
    private static FunctionCall runTurn(InMemoryRunner runner, Session session, String message) {

        System.out.println("you: " + message);

        // Calls seen so far, by id. We need the whole call later, not just its id, because
        // answering it requires the name too.
        Map<String, FunctionCall> longRunningCalls = new LinkedHashMap<>();
        FunctionCall[] pending = {null};

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(message)))
                .blockingForEach(event -> {

                    event.functionCalls().forEach(call -> {
                        System.out.println("  [tool call]   " + call.name().orElse("?")
                                + " " + call.args().orElse(Map.of()));

                        // Registering the tool as long-running marks EVERY call to it, whatever
                        // the method ends up returning. ADK does not know our 5000 rule, so
                        // this set alone does not mean "waiting for a human".
                        event.longRunningToolIds()
                                .filter(ids -> ids.contains(call.id().orElse("")))
                                .ifPresent(ids -> longRunningCalls.put(call.id().orElse(""), call));
                    });

                    event.functionResponses().forEach(response -> {
                        Map<String, Object> body = response.response().orElse(Map.of());
                        System.out.println("  [tool result] " + body);

                        // The business rule decides, not the framework. A call is only really
                        // pending if it was long-running AND our method said so.
                        FunctionCall call = longRunningCalls.get(response.id().orElse(""));

                        if (call != null && "PENDING_APPROVAL".equals(body.get("status"))) {
                            System.out.println("  [PAUSED]      waiting for a human on "
                                    + body.get("ticket"));
                            pending[0] = call;
                        }
                    });

                    // Skip the function call and function response events. stringifyContent()
                    // on those prints the raw object, which is noise next to the real reply.
                    if (event.finalResponse()
                            && event.functionResponses().isEmpty()
                            && event.functionCalls().isEmpty()) {

                        String text = event.stringifyContent().strip().replace("\n", " ");
                        if (!text.isEmpty()) {
                            System.out.println("  agent: " + text);
                        }
                    }
                });

        return pending[0];
    }
}
