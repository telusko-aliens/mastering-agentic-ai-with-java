package com.telusko.mcpclientapp.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface SupportAgent
{
    @SystemMessage("""
            You are Telusko support.
            Use the tools available to you rather than guessing.
            Answer in two short sentences.
            """)
    Result<String> ask(@MemoryId String userId, @UserMessage String question);
}
