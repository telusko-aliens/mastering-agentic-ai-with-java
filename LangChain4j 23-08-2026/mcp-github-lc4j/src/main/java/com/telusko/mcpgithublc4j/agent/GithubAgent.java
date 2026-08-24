package com.telusko.mcpgithublc4j.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface GithubAgent
{
    @SystemMessage(
            """
                      You are a GitHub research assistant.
                                Use the tools available to you rather than guessing.
                                When a tool can return a list, ask for at most 5 items.
                                Answer in three short sentences.
                    """
    )
    Result<String> ask(@MemoryId String userId,
                       @UserMessage String question);
}
