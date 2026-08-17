package com.telusko;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.tool.ToolExecution;

import java.util.ArrayList;
import java.util.List;

public class LowLevelTools 
{
    interface SupportAgent
    {
//        @SystemMessage("You are Telusko Support. Answer in one or two short sentence")
//        String ask(String question);
    @SystemMessage("""
            You are Telusko support.
            use tools available to you rather than guessing.
            Answer in two short sentences and state the dates you used
            """ )
    String ask(String question);

        @SystemMessage("""
            You are Telusko support.
            use tools available to you rather than guessing.
            Answer in two short sentences and state the dates you used
            """ )
        Result<String> ask(@MemoryId String userId, @dev.langchain4j.service.UserMessage String question);
    }

    public static void main(String[] args)
    {

        ChatModel chatModel = Models.chat();
        SupportAgent agent = AiServices.builder(SupportAgent.class)
                .chatModel(chatModel)
                .tools(new SupportTools())
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();
        
        ToolSpecification findEnrollment = ToolSpecification.builder()
                .name("findEnrollment")
                .description("Looks up a students's enrolment record using their email ids")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("email", "Email of the student")
                        .build())
                .build();
        List<ChatMessage> messages = new ArrayList<>();
        String message = "What Course is harsh@gmail.com enrolled in?";
        messages.add(UserMessage.from(message));

        ChatResponse first = chatModel.chat(ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(findEnrollment)
                .build());

        AiMessage aiMessage = first.aiMessage();
        //System.out.println("Answer: " + aiMessage.text());
        messages.add(aiMessage);
        System.out.println("Is tool required : "+ aiMessage.hasToolExecutionRequests());

        ToolExecutionRequest request = aiMessage.toolExecutionRequests().get(0);
        System.out.println("Tool "+ request.name());
        System.out.println("Arguments : "+ request.arguments());


        SupportTools supportTools = new SupportTools();
       var enrolment = supportTools.findEnrolment("shramik@gmail.com");
       messages.add(ToolExecutionResultMessage.from(request, enrolment.toString()));
        ChatResponse second = chatModel.chat(ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(findEnrollment)
                .build());
        System.out.println("Answer: " + second.aiMessage().text());


        System.out.println("------------------------------------------------");
        System.out.println(agent.ask("What course @harsh@gmail.com enrolled in?"));
        System.out.println(agent.ask("How much did harsh@gmail.com pay"));
        System.out.println(agent.ask("When did harsh@gmail.com enroll?"));
        System.out.println(agent.ask("I am harsh@gmail.com, can i get a refund?"));
        System.out.println(agent.ask("I am Shramik@gmail.com, can i get a refund?"));
        System.out.println("_______________________________________________");
        Result<String> result = agent.ask(
                "shramik",
                "I am Shramik@gmail.com, can i still get refund"
        );
        System.out.println("Answer: " + result.content());
        System.out.println(agent.ask("shramik", "And how much would I get back").content());

        for (ToolExecution execution : result.toolExecutions()) {
            System.out.println(
                    "Ran    : " + execution.request().name()
                            + " " + execution.request().arguments()
                            + " -> " + execution.result()
            );
        }


    }
}
