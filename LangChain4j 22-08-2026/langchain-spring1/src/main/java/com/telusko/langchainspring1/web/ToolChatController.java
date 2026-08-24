package com.telusko.langchainspring1.web;

import com.telusko.langchainspring1.lc4j.SupportAgent;
import dev.langchain4j.service.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RequestMapping("/api/tool")
@RestController
public class ToolChatController
{
    private SupportAgent supportAgent;

    public ToolChatController(SupportAgent supportAgent)
    {
        this.supportAgent = supportAgent;
    }
    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Request request)
    {
        Result<String> result = supportAgent.ask(request.userId(), request.question());
        return Map.of("answer ", result.content());

    }

    @PostMapping("/trace")
    public Map<String, Object> trace(@RequestBody Request request) {

        // Ask the AI agent to process the question.
        Result<String> result = supportAgent.ask(
                request.userId(),
                request.question()
        );

        // Extract details of every tool execution.
        List<Map<String, String>> toolCalls =
                result.toolExecutions().stream()
                        .map(execution -> Map.of(
                                // Which tool was called?
                                "tool", execution.request().name(),

                                // What arguments did the LLM provide?
                                "arguments", execution.request().arguments(),

                                // What did the Java tool return?
                                "result", execution.result()
                        ))
                        .toList();
        return Map.of("answer", result.content(), "toolCalls", toolCalls,
                "total tokens : ", result.tokenUsage().totalTokenCount());
    }


    public record Request(
            String userId,
            String question
    ) {
    }

}
