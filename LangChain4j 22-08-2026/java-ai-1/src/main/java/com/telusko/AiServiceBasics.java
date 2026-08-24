package com.telusko;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public class AiServiceBasics
{
    interface CourseAssistant
    {
        String chat(String userMessage);

        @SystemMessage(
                """
                You are Telusko Bot, the assistant for a Java and Spring Boot training company.
                Answer in at most three sentences.
                If the question is not about programming, politely refuse.
                """
        )
        String ask(String userMessage);


        @UserMessage("Explain {{topic}} to a Java developer who has never seen it before in 1 line")
        String explain(@V("topic")String topic);

        @UserMessage("Explain {{topic}} to a {{level}} Use at most {{lines}}lines")
        String explainFor(@V("topic") String topic,
                          @V("level") String level,
                          @V("lines") int lines);
    }


    public static void main(String[] args)
    {
        CourseAssistant assistant = AiServices.create(CourseAssistant.class, Models.chat());
//        System.out.println("-- Plain Chat ---");
//        System.out.println(assistant.chat("What is JAR file?"));
//        System.out.println("_________________________________");

        System.out.println("--- System Message Example --");
        System.out.println(assistant.ask("What is SpringBoot"));
        System.out.println("-----------------------------------");

        System.out.println("--- System Message Example irrelevant --");
        System.out.println(assistant.ask("Give me recipe fr Chicken Biryani"));
        System.out.println("-----------------------------------");

        System.out.println("--- User Message Prompt Template ---");
        System.out.println(assistant.explain("Dependency Injection"));
        System.out.println("-----------------------------------------");

        System.out.println("--- Multiple Param  Prompt Template ---");
        System.out.println(assistant.explainFor("Garbage Collection", "Junior", 5));
        System.out.println("-----------------------------------------");

        System.out.println("Our Ai Assistant implemented by "+assistant.getClass().getName() );

    }
}
