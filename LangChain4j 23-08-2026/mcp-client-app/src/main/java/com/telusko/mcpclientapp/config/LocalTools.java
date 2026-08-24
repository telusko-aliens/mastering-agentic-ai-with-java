package com.telusko.mcpclientapp.config;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class LocalTools
{
    @Tool("Escalates a student to a human mentor when automated answer is not enough")
    String escalateToMentor( @P("the student's email address") String email,
                             @P("why it is being escalated") String reason) {

        return "Ticket TEL-4471 created for " + email
                + ". A mentor will reply within 24 hours.";
    }
}
