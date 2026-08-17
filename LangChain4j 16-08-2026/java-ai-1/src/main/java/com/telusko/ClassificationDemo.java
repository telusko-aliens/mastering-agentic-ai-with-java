package com.telusko;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public class ClassificationDemo
{
    enum Module
    {
        @Description("Questions about java, OOP, collections, JVM")
        JAVA_BASICS,
        @Description("Questions about Spring, Spring Boot, REST APIs, JPA")
        SPRING_BOOT,
        @Description("Questions about AI and Langchain4j")
        LANGCHAIN4J,

        @Description("Payment, Login, certification or other support related issues")
        SUPPORT
    }
    interface DoubtRouter
    {
        @UserMessage("Which part of the course does this student doubt belong to?\n\n: {{doubt}}")
        Module route(@V("doubt") String doubt);

        @UserMessage("Does this needs a human mentor rather than an automated answer?\n\n: {{doubt}}")
        boolean needsMentor(@V("doubt") String doubt);

    }

    public static void main(String[] args) {

        DoubtRouter assistant = AiServices.create(DoubtRouter.class, Models.chat());
        String[] doubts=
                {
                        "I paid yesterday but the course is not showing in My Courses. ",
                        "What is the difference between an abstract class and an interface",
                        "My ChatMemory is not remembering anything between two calls in my ai application"

                };
        for(String doubt:doubts)
        {
           Module module =assistant.route(doubt);
           String queue= switch (module)
            {
                case SUPPORT ->  "Support team";
                case JAVA_BASICS, SPRING_BOOT -> "Mentor team";
                case LANGCHAIN4J -> "AI team";
            };
            System.out.println("Question : "+ doubt);
            System.out.println("Routed to : "+ module + " Queue: "+ queue);
        }
        System.out.println(assistant.needsMentor("I have been stuck for three days and nothing is helping me"));

    }
}
