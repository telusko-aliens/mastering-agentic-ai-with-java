package com.telusko;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;

import java.util.concurrent.CountDownLatch;

public class TokenStreamDemo
{
    interface Assistant
    {
        @SystemMessage("You are Telusko Bot. explain Cleary with short paragraphs")
        TokenStream chat(String message);
    }

    public static void main(String[] args) throws Exception
    {
        Assistant assistant = AiServices.builder(Assistant.class)
                .streamingChatModel(Models.streamingChat())
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        TokenStream stream = assistant.chat("How JVM Manages Memory");
        CountDownLatch done = new CountDownLatch(1);
        stream
                .onPartialResponse(System.out::print)
                .onCompleteResponse(response -> {

                    System.out.println("\n\n--- Completed ---");

                    // Response metadata.
                    System.out.println("Finish Reason : "
                            + response.metadata().finishReason());

                    System.out.println("Input Tokens : "
                            + response.metadata()
                            .tokenUsage()
                            .inputTokenCount());

                    System.out.println("Output Tokens : "
                            + response.metadata()
                            .tokenUsage()
                            .outputTokenCount());

                    done.countDown();
                })
                .onError(error -> {
                    System.out.println("Error "+ error.getMessage());
                })
                .start();

        done.await();
    }
}
