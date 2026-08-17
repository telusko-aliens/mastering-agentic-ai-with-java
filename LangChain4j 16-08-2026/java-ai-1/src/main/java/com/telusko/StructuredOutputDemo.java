package com.telusko;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

public class StructuredOutputDemo
{
    record CourseEnquiry(

            @Description("Full name of the person")
            String name,

            @Description("Course they want to learn")
            String topic,

            @Description("Years of experience")
            int experienceYears,

            @Description("Whether they are ready to enroll")
            boolean readyToEnrol
    ) {}


    interface EnquiryReader
    {
        @UserMessage("Extract the enquiry details from this message:\n\n{{message}}")
        CourseEnquiry read(@V("message") String message);
        @UserMessage("List the programming topics mentioned in this text: {{text}}")
        List<String> topicsIn(@V("text")String text);

        @UserMessage("Extract the enquiry details from this message:\n\n{{message}}")
        Result<CourseEnquiry> readWithMetadata(@V("message")String message);

    }
    public static void main(String[] args)
    {
        EnquiryReader assistant = AiServices.create(EnquiryReader.class, Models.chat());
        String message=
                """
                        Hi, I am Ramesh Kumar.
                                       I have been working in Java for about 4 years
                                       and I want to learn LangChain4j properly.
                                       I want to start with the next batch.
                   
                         """
                ;

        CourseEnquiry enquiry=assistant.read(message);
        System.out.println(enquiry);
        if(enquiry.readyToEnrol() && enquiry.experienceYears() >=1)
        {
            //write business logic to send to slaes team or enrolment team
            System.out.println("Send info to Sales team");
        }
        else {
            System.out.println("Send info to Marketing team for nurturing and give free preview course");
        }

        String text="I know SpringBoot and Java, However I dont know redis and kafka well";
        System.out.println( assistant.topicsIn(text));
        Result<CourseEnquiry> result = assistant.readWithMetadata(message);
        System.out.println(result.content());
        System.out.println("---------------------");
        System.out.println("Input Token "+ result.tokenUsage().inputTokenCount());
        System.out.println("---------------------");
        System.out.println("Output Token "+ result.tokenUsage().outputTokenCount());

    }
}
