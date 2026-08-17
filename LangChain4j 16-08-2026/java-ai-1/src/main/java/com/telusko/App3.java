package com.telusko;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;

public class App3
{
    public static void main(String[] args) {
        ChatModel openAI = OpenAiChatModel.builder()
                .apiKey("your-api-key")
                .modelName("gpt-4o-mini")
                .temperature(0.8)
                .timeout(Duration.ofSeconds(30))
                .build();

        ChatModel local= OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("mistral:latest")
                .temperature(0.3)
                .timeout(Duration.ofSeconds(80))
                .maxRetries(0)
                .build();

        askTheQuestion("OpenAI", openAI);
        System.out.println("********************************************");
        askTheQuestion("Local", local);

    }

    private static void askTheQuestion(String tag, ChatModel model)
    {
        try
        {
            System.out.println(model.chat("In One Sentence: Explain Dependency Injection"));
        }
        catch (Exception e)
        {
            System.out.println(" Error");
        }
    }
}
