package com.telusko;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.Scanner;

public class App2
{
    public static void main(String[] args) throws Exception
    {
                ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey("your-api-key")
                .modelName("gpt-4o-mini")
                        .temperature(0.8)
                        .timeout(Duration.ofSeconds(30))
                .build();

        MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);

        memory.add(
                SystemMessage.from(  "You are Telusko Bot, an assistant for a Java and Spring Boot training company. " +
                        "Keep every answer under three sentences. " +
                        "If you do not know something, say so.")
        );

        Scanner scanner = new Scanner(System.in);
        System.out.println("Telusko Bot is ready. Chat Now. Also Type   'exit' to quit");

        while(true)
        {
            System.out.print("You : ");
            String input =scanner.nextLine();

            if("exit".equalsIgnoreCase(input.trim()))
            {
                break;
            }

            memory.add(UserMessage.from(input));

            AiMessage aiMessage = chatModel.chat(memory.messages()).aiMessage();
            System.out.println("Bot : "+ aiMessage.text() + "\n");
            memory.add(aiMessage);

        }
    scanner.close();
        System.out.println("Bye Have a nice day");

    }
}
