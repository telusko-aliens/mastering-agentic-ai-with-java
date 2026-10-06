package com.telusko.tool;



import com.google.adk.tools.Annotations.Schema;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HUMAN-APPROVAL TOOL
 *
 * This tool processes refund requests.
 *
 *
 * The important business rule:
 *
 *      amount < 5000
 *          ↓
 *      approve automatically
 *
 *
 *      amount >= 5000
 *          ↓
 *      pending human approval
 *
 *
 * IMPORTANT:
 *
 * This Java method itself does NOT wait.
 *
 * It returns immediately.
 *
 * Human-in-the-loop behaviour happens because
 * Step6 registers this method as:
 *
 *      LongRunningFunctionTool
 *
 * instead of a normal FunctionTool.
 */
public class ApprovalTools {


    // ====================================================================
    // BUSINESS RULE
    // ====================================================================

    /*
     * Refunds of ₹5000 or above
     * need manager approval.
     *
     * Important:
     *
     * This rule belongs in JAVA,
     * not only in a prompt.
     *
     * Why?
     *
     * Prompt:
     *      model instruction / suggestion
     *
     * Java:
     *      deterministic business rule
     */
    public static final int APPROVAL_THRESHOLD = 5000;


    /*
     * Used only to generate demo approval ticket numbers.
     *
     * Example:
     *
     *      APR-4701
     *      APR-4702
     */
    private static final AtomicInteger TICKETS =
            new AtomicInteger(4700);


    // ====================================================================
    // REFUND TOOL
    // ====================================================================

    /*
     * @Schema helps describe the tool/method to the model.
     */
    @Schema(
            description =
                    "Request a refund for a passenger. Refunds of 5000 rupees or more "
                            + "need a manager to approve them and will come back as pending."
    )
    public static Map<String, Object> requestRefund(


            /*
             * Parameter metadata visible in the function/tool schema.
             */
            @Schema(
                    name = "pnr",
                    description = "The six character PNR code"
            )
            String pnr,


            @Schema(
                    name = "amount",
                    description = "Refund amount in rupees"
            )
            int amount) {


        // ================================================================
        // SMALL REFUND
        // ================================================================

        /*
         * If below the threshold,
         * approve immediately.
         */
        if (amount < APPROVAL_THRESHOLD) {

            return Map.of(

                    "status", "APPROVED",

                    "pnr", pnr,

                    "amount", amount,

                    "message",
                    "Refund processed automatically"
            );
        }


        // ================================================================
        // LARGE REFUND
        // ================================================================

        /*
         * Large refund.
         *
         * In a real application this is where you might:
         *
         *      insert into database
         *      publish to a queue
         *      create approval ticket
         *      notify manager
         *
         *
         * But notice:
         *
         * WE DO NOT BLOCK.
         *
         * We simply create a reference and return.
         */
        String ticket =
                "APR-" + TICKETS.incrementAndGet();


        return Map.of(

                "status", "PENDING_APPROVAL",

                "ticket", ticket,

                "pnr", pnr,

                "amount", amount,

                "message",
                "Sent to a manager for approval"
        );
    }
}
