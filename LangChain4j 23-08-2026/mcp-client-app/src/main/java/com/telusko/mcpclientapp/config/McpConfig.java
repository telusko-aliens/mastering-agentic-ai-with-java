package com.telusko.mcpclientapp.config;

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

@Configuration
public class McpConfig 
{
    @Bean
    public McpClient mcpClient(@Value("${telusko.mcp.server-jar}") String serverJar)
    {
        McpTransport transport = StdioMcpTransport.builder()
                .command(List.of( "java",
                        "-jar",
                        serverJar)).logEvents(true)
                .build();

        return DefaultMcpClient.builder()
                .key("telusko-support")
                .transport(transport)
                .initializationTimeout(Duration.ofMinutes(2))
                .build();
    }

    @Bean
    public ToolProvider mcpToolProvider(McpClient mcpClient) {

        return McpToolProvider.builder()
                .mcpClients(mcpClient)
                .build();
    }
}
