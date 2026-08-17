package com.telusko;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;

public final class Models
{
    private Models()
    {

    }
    public static ChatModel chat()
    {
        return OpenAiChatModel.builder()
                .apiKey("your-api-key")
                .modelName("gpt-4o-mini")
                .temperature(0.3)
                .timeout(Duration.ofSeconds(30))
                .build();
    }
    public static StreamingChatModel streamingChat()
    {
        return OpenAiStreamingChatModel
                .builder()
                .apiKey("your-api-key")
                .modelName("gpt-4o-mini")
                .timeout(Duration.ofSeconds(30))
                .build();
    }

}
