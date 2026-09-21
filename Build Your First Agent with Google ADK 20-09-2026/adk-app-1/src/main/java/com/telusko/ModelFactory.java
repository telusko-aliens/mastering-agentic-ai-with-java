package com.telusko;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.google.adk.models.ApigeeLlm;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.Claude;
import com.google.adk.models.Gemini;

public class ModelFactory
{

    private ModelFactory()
    {

    }
    public static BaseLlm current()
    {
        String provider= env("ADK_PROVIDER", "gemini").toLowerCase();

        return switch (provider)
        {
            case "ollama" -> ollama();
            case "claude" -> claude();
            case "gemini" -> gemini();
            default ->   throw new IllegalArgumentException("Unknown ADK_PROVIDER '" + provider + "'. Use gemini, ollama or claude.");
        };
    }



    private static BaseLlm gemini()
    {
        String apiKey="your-api-key;

        if (apiKey == null || apiKey.isBlank())
        {
            throw new IllegalStateException
                    (
                            """ 
                                    GOOGLE_API_KEY is not set. Get a key from https://aistudio.google.com/apikey, then: Windows : 
                                    set GOOGLE_API_KEY=your-key-here Linux : export GOOGLE_API_KEY=your-key-here Or run with no key at all: 
                                    set ADK_PROVIDER=ollama
                                    """);
        }


        return Gemini.builder()
//                .modelName("gemini-flash-latest")
                .modelName(env("GEMINI_MODEL", "gemini-flash-latest"))
                .apiKey(apiKey)
                .build();
    }

    private static BaseLlm claude()
    {
        String apiKey="your-api-key";

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not set. Get one from https://console.anthropic.com/");
        }
        return new Claude(env("CALUDE_MODEL", "claude-sonnet-4.5"),
                AnthropicOkHttpClient.builder().apiKey(apiKey).build());
    }


    private static BaseLlm ollama()
    {

        String model= env("OLLAMA_MODEL", "llama3.2");
        return ApigeeLlm.builder()
                .modelName("apigee/openai/"+model)
                .proxyUrl(env("OLLAMA_BASE_URL", "http://localhost:11434/v1"))
                .apiType(ApigeeLlm.ApiType.CHAT_COMPLETIONS)
                .build();
    }

//    private static BaseLlm openai()
//    {
//        String apiKey="";
//        if (apiKey == null || apiKey.isBlank()) {
//            throw new IllegalStateException("""
//                    OPENAI_API_KEY is not set.
//
//                    Get a key from https://platform.openai.com/api-keys, then:
//                      Windows : set OPENAI_API_KEY=your-key-here
//                      Linux   : export OPENAI_API_KEY=your-key-here
//
//                    Or run free on your own machine: set ADK_PROVIDER=ollama""");
//        }
//
//        return ApigeeLlm.builder()
//                .modelName("apigee/openai/" + env("OPENAI_MODEL", "gpt-4o"))
//                .proxyUrl(env("OPENAI_BASE_URL", "https://api.openai.com/v1"))
//                .apiType(ApigeeLlm.ApiType.CHAT_COMPLETIONS)
//                .build();
//
//    }


    public static String describe() {
        String provider = env("ADK_PROVIDER", "gemini").toLowerCase();

        return switch (provider) {
            case "ollama" -> "ollama / " + env("OLLAMA_MODEL", "llama3.2")
                    + " at " + env("OLLAMA_BASE_URL", "http://localhost:11434/v1");
            case "claude" -> "claude / " + env("CLAUDE_MODEL", "claude-sonnet-4-5");
            default -> "gemini / " + env("GEMINI_MODEL", "gemini-flash-latest");
        };
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

}
