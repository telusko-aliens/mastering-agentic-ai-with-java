package com.telusko;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
//import dev.langchain4j.service.SystemMessage;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main
{
    public static void main(String[] args) {

//        System.out.println("Hello and welcome to Telusko");
//        ChatModel chatModel = OpenAiChatModel.builder()
//                .apiKey(System.getenv("OPENAI_API_KEY"))
//                .modelName("gpt-4o-mini")
//                .build();
////        String response = chatModel.chat("Hi, Tell me about LangChain4j in short");
////        System.out.println(response);
//        SystemMessage systemMessage= SystemMessage.from(
//                "You are a Java trainer at Telusko. Answer in short bullet points. " +
//                        "Never answer questions that are not about programming."
//        );
//        UserMessage userMessage=UserMessage.from("Hi, Tell me a Joke on langchain4j");
//
//        ChatResponse response = chatModel.chat(systemMessage, userMessage);
//        AiMessage reply = response.aiMessage();
//        System.out.println("Answer: "+ reply);
//        System.out.println("Model used : "+ response.modelName());
//        System.out.println("Total In/Out Tokens used : "+ response.tokenUsage().totalTokenCount());
//        System.out.println("Total In Tokens used : "+ response.tokenUsage().inputTokenCount());
//        System.out.println("Total Out Tokens used : "+ response.tokenUsage().outputTokenCount());
//        System.out.println("Finish Reason : " + response.metadata().finishReason());
//
//        ///
//        System.out.println("---------------------------------------------------");
//
//        List<ChatMessage>  conversation = new ArrayList<>();
//        conversation.add(SystemMessage.from("You are a helpful assistant. Keep answers to one line"));
//
//        conversation.add(UserMessage.from("My name is Navin and I Teach java"));
//        AiMessage aiMessage1 = chatModel.chat(conversation).aiMessage();
//        System.out.println("First : " +aiMessage1.text());
//
//        conversation.add(aiMessage1);
//
//        conversation.add(UserMessage.from(
//                "What do I Teach"
//        ));
//        AiMessage aiMessage2 = chatModel.chat(conversation).aiMessage();
//        System.out.println("Second : " +aiMessage2.text());
//        conversation.add(aiMessage2);
////        conversation.add(UserMessage.from(
////                "I also teach Spring Boot and AI Engineering"
////        ));
//        AiMessage aiMessage3 = chatModel.chat(conversation).aiMessage();
//        System.out.println("Third : " +aiMessage3.text());
//        //conversation.add(aiMessage3);
//
//        conversation.add(UserMessage.from(
//                "What do I Teach"
//        ));
//        AiMessage aiMessage4 = chatModel.chat(conversation).aiMessage();
//        System.out.println("Four : " +aiMessage4.text());

//        OpenAiChatModel chatModel = OpenAiChatModel.builder()
//                .apiKey(System.getenv("OPENAI_API_KEY"))
//                .modelName("gpt-4o-mini")
//                .temperature(0.7)
//                .build();

//        ChatRequest request = ChatRequest.builder()
//                .messages(UserMessage.from("Give me the exact SQL to create a users table with id, email and created_at"))
//                .modelName("gpt-4o")
//                .temperature(0.0)
//                .maxOutputTokens(150)
//                .build();
//
//        ChatResponse response = chatModel.chat(request);
//        System.out.println(response.aiMessage().text());
//
//        System.out.println(response.metadata().modelName());


//        OpenAiChatModel chatModel = OpenAiChatModel.builder()
//                .apiKey(System.getenv("OPENAI_API_KEY"))
//                .modelName("gpt-4o-mini")
////                .temperature(0.0)
//                .temperature(1.2)
//                .build();
//        for(int i=1; i<4; i++)
//        {
//            System.out.println(chatModel.chat(i + ": "+ "Give me one tagline for Java Training Company. Only the tagline"));
//
//        }
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                // Controll creativity
                //0.0 ---> 2.0
                .temperature(1.4)
                //Helps control response length
//                .topP(0.9)
                .maxCompletionTokens(500)
                // Reduces repeated words
                //-2.0 to 2.0
                .frequencyPenalty(1.0)
                .presencePenalty(0.0)
                .stop(java.util.List.of("###"))
                .timeout(Duration.ofSeconds(30))
                .maxRetries(2)
//                .logRequests(true)
//                .logResponses(true)
                .build();
//        for(int i=1; i<4; i++)
//        {
//            System.out.println(chatModel.chat(i + ": "+ "Tell me in short about Java"));
//
//        }
                   System.out.println(chatModel.chat( "Tell me in short about Java within 3 lines bullet points"));

    }
}