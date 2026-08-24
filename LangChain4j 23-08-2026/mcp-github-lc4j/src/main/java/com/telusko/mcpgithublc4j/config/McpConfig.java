package com.telusko.mcpgithublc4j.config;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration
public class McpConfig
{
    private static final int MAX_TOOL_RESULT = 20_000;

    @Bean
    public McpClient mcpClient(@Value("${telusko.mcp.npx}") String npx,
                               @Value("${telusko.github.token}") String token)
    {
        McpTransport transport = StdioMcpTransport.builder()
                .command(List.of(
                        npx,
                        "-y",
                        "@modelcontextprotocol/server-github"
                ))
                .environment(Map.of(
                        "GITHUB_PERSONAL_ACCESS_TOKEN",
                        token
                ))
                .logEvents(true)
                .build();

        return DefaultMcpClient.builder()
                .key("github")
                        .transport(transport)
                                .initializationTimeout(Duration.ofMinutes(2))
                                        .build();


    }
    @Bean
    public ToolProvider mcpToolProvider(McpClient mcpClient) {

        return McpToolProvider.builder()
                .mcpClients(mcpClient)
                .filterToolNames("search_repositories",
                        "list_issues",
                        "get_file_contents",
                        "list_commits",
                        "create_issue")
                .toolWrapper(executor -> (request, memoryId) -> {

                    // Execute the MCP tool.
                    String result = executor.execute(request, memoryId);

                    // If the response is small enough, return it as-is.
                    if (result.length() <= MAX_TOOL_RESULT) {
                        return result;
                    }
                    return result.substring(0, MAX_TOOL_RESULT)
                            + " ... [truncated]";
                })
                .build();
    }


}
