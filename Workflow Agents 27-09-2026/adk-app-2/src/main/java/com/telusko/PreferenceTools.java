package com.telusko;

import com.google.adk.sessions.State;
import com.google.adk.tools.Annotations.*;
import com.google.adk.tools.ToolContext;

import java.util.LinkedHashMap;
import java.util.Map;

public class PreferenceTools
{

    @Schema(description = "Save the passenger's seat preference so it is remembered for future "
            + "bookings. Use this when they say they prefer a window or aisle seat.")
    public static Map<String, Object> saveSeatPreference(
            @Schema(name = "preference", description = "Either 'window' or 'aisle'")
            String preference,
            ToolContext toolContext) {

        String value = preference == null ? "" : preference.trim().toLowerCase();

        if (!value.equals("window") && !value.equals("aisle")) {
            return Map.of("saved", false,
                    "message", "Preference must be either window or aisle");
        }

        // user: so it outlives this conversation. Drop the prefix and the preference is
        // forgotten the moment the chat ends, which is exactly the bug this lesson prevents.
        toolContext.state().put(State.USER_PREFIX + "seat", value);

        return Map.of("saved", true, "preference", value);
    }

    @Schema(description = "Read back everything currently remembered about the passenger.")
    public static Map<String, Object> getPreferences(ToolContext toolContext) {

        Map<String, Object> known = new LinkedHashMap<>();

        toolContext.state().forEach((key, value) -> {
            // Only report what belongs to the user. Internal session keys are noise here.
            if (key.startsWith(State.USER_PREFIX)) {
                known.put(key.substring(State.USER_PREFIX.length()), value);
            }
        });

        return known.isEmpty()
                ? Map.of("known", false, "message", "Nothing is remembered yet")
                : Map.of("known", true, "preferences", known);
    }
}
