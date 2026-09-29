package com.telusko;

import com.google.adk.tools.Annotations.*;

import java.util.LinkedHashMap;
import java.util.Map;

public class BookingTools
{

    private static final Map<String, Map<String, Object>> BOOKINGS = Map.of(
            "TL1001", Map.of("pnr", "TL1001", "passenger", "Shramik", "route", "PUN to GOA",
                    "date", "2026-10-02", "status", "CONFIRMED", "seat", "12A"),
            "TL1002", Map.of("pnr", "TL1002", "passenger", "Navin", "route", "BOM to DEL",
                    "date", "2026-10-05", "status", "CANCELLED", "seat", "4C"));

    @Schema(description = "Look up a flight booking by its PNR code and return the passenger, "
            + "route, date and status.")
    public static Map<String, Object> getBooking(
            @Schema(name = "pnr", description = "The six character PNR code, for example TL1001")
            String pnr) {

        String key = pnr == null ? "" : pnr.trim().toUpperCase();
        Map<String, Object> booking = BOOKINGS.get(key);

        // A missing booking is not an exception. Throwing would abort the turn; returning a
        // "found: false" map lets the model read the answer and tell the user politely.
        if (booking == null) {
            return Map.of("found", false, "pnr", key,
                    "message", "No booking exists with that PNR");
        }

        Map<String, Object> result = new LinkedHashMap<>(booking);
        result.put("found", true);
        return result;
    }

    @Schema(description = "Calculate the cancellation fee for a booking. Returns the fee in "
            + "rupees and the refund the passenger would receive.")
    public static Map<String, Object> cancellationFee(
            @Schema(name = "pnr", description = "The six character PNR code")
            String pnr,

            @Schema(name = "hoursBeforeDeparture", description = "Hours remaining before departure")
            int hoursBeforeDeparture) {

        Map<String, Object> booking = getBooking(pnr);

        if (Boolean.FALSE.equals(booking.get("found"))) {
            return booking;
        }

        // The rule lives in Java, not in the prompt. A model asked to apply a fee table gets it
        // right most of the time, and the times it does not are a passenger quoted a refund the
        // airline will not pay.
        int fare = 4500;
        int fee = hoursBeforeDeparture >= 24 ? 0 : (int) (fare * 0.25);

        return Map.of(
                "pnr", pnr.toUpperCase(),
                "farePaid", fare,
                "cancellationFee", fee,
                "refund", fare - fee,
                "reason", hoursBeforeDeparture >= 24
                        ? "Cancelled more than 24 hours before departure, so no fee applies"
                        : "Cancelled within 24 hours of departure, so a 25 percent fee applies");
    }

}
