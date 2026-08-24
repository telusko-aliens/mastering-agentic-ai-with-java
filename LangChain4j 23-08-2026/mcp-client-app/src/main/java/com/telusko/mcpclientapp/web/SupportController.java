package com.telusko.mcpclientapp.web;

import com.telusko.mcpclientapp.agent.SupportAgent;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RequestMapping("/api/support")
@RestController
public class SupportController
{
    private SupportAgent agent;

    private McpClient mcpClient;

    public SupportController(SupportAgent agent, McpClient mcpClient)
    {
        this.agent = agent;
        this.mcpClient = mcpClient;
    }
    @GetMapping("/tools")
    public List<Map<String, String>> tools() {

        return mcpClient.listTools().stream()
                .map(tool -> Map.of(
                        "name", tool.name(),
                        "description", tool.description()))
                .toList();
    }

    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Request request) {

        Result<String> result =
                agent.ask(request.userId(), request.question());

        return Map.of("answer", result.content());
    }

    @PostMapping("/trace")
    public Map<String, Object> trace(@RequestBody Request request) {

        Result<String> result =
                agent.ask(request.userId(), request.question());


        /*
         * Get the list of tools provided by the MCP server.
         *
         * We use this later to identify where each tool executed.
         */
        List<String> remote = mcpClient.listTools().stream()
                .map(ToolSpecification::name)
                .toList();

        List<Map<String, String>> toolCalls =
                result.toolExecutions().stream()
                        .map(execution -> Map.of(

                                "tool",
                                execution.request().name(),

                                "arguments",
                                execution.request().arguments(),

                                "result",
                                execution.result(),

                                /*
                                 * If the tool exists in the MCP server's
                                 * tool list → it ran on MCP server.
                                 *
                                 * Otherwise → it is a local tool
                                 * inside this Spring Boot application.
                                 */
                                "ranOn",
                                remote.contains(execution.request().name())
                                        ? "mcp server"
                                        : "this app"))
                        .toList();
        return Map.of(
                "answer", result.content(),
                "toolCalls", toolCalls,
                "totalTokens",
                result.tokenUsage().totalTokenCount()
        );
    }







    public record Request(
            String userId,
            String question) {
    }


}
