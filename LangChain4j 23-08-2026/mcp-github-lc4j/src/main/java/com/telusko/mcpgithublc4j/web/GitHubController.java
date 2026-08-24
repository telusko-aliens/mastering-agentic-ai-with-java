package com.telusko.mcpgithublc4j.web;

import com.telusko.mcpgithublc4j.agent.GithubAgent;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RequestMapping("/api/mcp")
@RestController
public class GitHubController
{

    private GithubAgent githubAgent;

    private McpClient mcpClient;

    public GitHubController(GithubAgent githubAgent, McpClient mcpClient) {
        this.githubAgent = githubAgent;
        this.mcpClient = mcpClient;
    }
    @PostMapping("/ask")
    public Map<String, String>ask(@RequestBody Request request)
    {
        Result<String> result = githubAgent.ask(request.userId(), request.question());
        return Map.of("answer", result.content());
    }
    @GetMapping("/tools")
    public Map<String, Object> tools() {

        // Ask the MCP Server for its complete tool list.
        List<String> all = mcpClient.listTools().stream()

                // Get only the tool name.
                .map(tool -> tool.name())

                .toList();

        // Return the number of tools and their names.
        return Map.of(
                "count", all.size(),
                "tools", all
        );
    }

    @PostMapping("/trace")
    public Map<String, Object> trace(@RequestBody Request request) {

        // Ask the AI the question.
        Result<String> result =
                githubAgent.ask(request.userId(), request.question());


        // Get every tool execution performed by the AI.
        List<Map<String, String>> toolCalls =
                result.toolExecutions().stream()

                        // For each tool call, get:
                        // - tool name
                        // - arguments sent to the tool
                        .map(execution -> Map.of(
                                "tool", execution.request().name(),
                                "arguments", execution.request().arguments()
                        ))

                        .toList();


        // Return everything useful for debugging/learning.
        return Map.of(
                "answer", result.content(),
                "toolCalls", toolCalls,
                "totalTokens", result.tokenUsage().totalTokenCount()
        );
    }



    public record Request(
            String userId,
            String question
    ) {
    }
}
