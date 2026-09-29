package com.telusko;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.Annotations.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class WeatherTools
{
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(
                            Duration.ofSeconds(10)
                    ).build();

    private static final ObjectMapper JSON =
            new ObjectMapper();

    private static final Map<String, double[]> CITIES =
            Map.of(
                    "pune",
                    new double[]{18.52, 73.86},

                    "mumbai",
                    new double[]{19.08, 72.88},

                    "delhi",
                    new double[]{28.61, 77.21},

                    "bangalore",
                    new double[]{12.97, 77.59},

                    "goa",
                    new double[]{15.30, 74.12},

                    "chennai",
                    new double[]{13.08, 80.27}
            );

    @Schema(description = "Get the current temperature and wind speed for an Indian city. "
            + "Supported cities: Pune, Mumbai, Delhi, Bangalore, Goa, Chennai.")
    public static Map<String, Object> getWeather(
            @Schema(name = "city", description = "City name, for example Pune")
            String city) {

        String key = city == null ? "" : city.trim().toLowerCase();
        double[] coords = CITIES.get(key);

        if (coords == null) {
            return Map.of("found", false, "city", city,
                    "message", "That city is not supported. Try Pune, Mumbai, Delhi, "
                            + "Bangalore, Goa or Chennai.");
        }

        String url = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + coords[0]
                + "&longitude=" + coords[1]
                + "&current=temperature_2m,wind_speed_10m";

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    // Always time out a call made on the model's behalf. Without this a slow API
                    // hangs the whole turn and the user just watches a blank screen.
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Map.of("found", false, "city", city,
                        "message", "Weather service returned " + response.statusCode());
            }

            JsonNode current = JSON.readTree(response.body()).path("current");

            // Four fields out of a response with about thirty. The model does not need the
            // generation time or the timezone abbreviation, and every field we skip is tokens
            // we do not pay for on this turn and every turn after it.
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("city", city);
            result.put("temperatureC", current.path("temperature_2m").asDouble());
            result.put("windSpeedKmh", current.path("wind_speed_10m").asDouble());
            result.put("observedAt", current.path("time").asText());
            return result;

        } catch (Exception e) {
            // Never let an exception escape a tool. It aborts the whole turn and the user sees
            // a stack trace instead of an answer. Hand the failure back as data and let the
            // model apologise properly.
            return Map.of("found", false, "city", city,
                    "message", "Could not reach the weather service: " + e.getClass().getSimpleName());
        }
    }
}
