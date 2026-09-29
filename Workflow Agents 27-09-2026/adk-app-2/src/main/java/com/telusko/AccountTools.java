package com.telusko;

import com.google.adk.tools.Annotations.*;
import com.google.adk.tools.ToolContext;

import java.util.List;
import java.util.Map;

public class AccountTools
{
    private static final Map<String, List<String>> BY_EMAIL = Map.of(

            "shramik@telusko.com",
            List.of("TL1001 PUN to GOA on 2026-10-02"),

            "navin@telusko.com",
            List.of("TL1002 BOM to DEL on 2026-10-05")
    );
    @Schema(description =
                        "Get the bookings belonging to the signed in user. "
                                + "Takes no arguments: the user is identified "
                                + "from the session, not from the conversation.")
    public static Map<String, Object> getMyBookings(ToolContext toolContext)
    {
           Object email= toolContext.state()
                    .get("user:email");

        if (email == null)
        {

            return Map.of(
                    "authenticated", false,
                    "message",
                    "Nobody is signed in. Ask the user to sign in first."
            );
        }

        List<String> bookings =
                BY_EMAIL.getOrDefault(
                        email.toString(),
                        List.of()
                );
        return Map.of(

                "count", bookings.size(),
                "bookings", bookings
        );
    }


    //   ToolContext toolContext will be injected by ADK and
    //it will have Session, statem runtime content
    @Schema(
            description =
                    "Check the loyalty points balance for the signed in user. "
                            + "Requires the loyalty service to be configured."
    )
    public static Map<String, Object> getLoyaltyPoints(
            ToolContext toolContext) {

        /*
         * Again, determine identity from trusted session state.
         */
        Object email =
                toolContext.state().get("user:email");

        if (email == null) {

            return Map.of(
                    "authenticated", false,
                    "message", "Nobody is signed in."
            );
        }

            String apiKey =
                    System.getenv("LOYALTY_API_KEY");

            if (apiKey == null || apiKey.isBlank()) {

                /*
                 * Configuration problem returned as data.
                 *
                 * The model can now explain:
                 *
                 * "The loyalty service is unavailable."
                 *
                 * instead of the whole agent turn crashing.
                 */
                return Map.of(
                        "available", false,
                        "message",
                        "The loyalty service is not configured right now."
                );
            }

            return Map.of(
                    "available", true,
                    "user", email,
                    "points", 12400,
                    "tier", "GOLD"
            );

        }

    }
