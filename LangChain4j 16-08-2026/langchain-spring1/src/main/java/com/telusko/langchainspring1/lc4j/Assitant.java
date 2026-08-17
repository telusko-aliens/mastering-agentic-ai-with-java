package com.telusko.langchainspring1.lc4j;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface Assitant
{
    @SystemMessage(
            """
            You are Telusko Bot, the assistant for a Java and Spring Boot training company.
            Answer in at most three sentences.
            If the question is not about programming, politely refuse.
            """
    )
    String chat(String userMessage);

    @SystemMessage("You are Telusko Bot. The assistant for a Java and Spring Boot training company")
    String chat(@MemoryId String userId, @UserMessage String message);

    @SystemMessage("You are a strict but encouraging app Code reviewer")
    @UserMessage("""
            Review this {{language}} code for a {{level}} developer.

            List at most three issues, most important first.

            ```
            {{code}}
            ```
            """)
    String reviewCode(@V("language") String language,
                      @V("level") String level,
                      @V("code") String code);
}
