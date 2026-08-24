package com.telusko;

import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.community.mcp.server.transport.StdioMcpServerTransport;
import dev.langchain4j.mcp.protocol.McpImplementation;

import java.util.List;

public class SupportMcpServer
{
    //create our mcp server

    public static void main(String[] args) throws Exception
    {
        McpServer server = new McpServer(
                List.of(new SupportTools()),
                new McpImplementation("telusko-support", "1.0.0"));

        System.err.println("[server] telusko-support ready");

        try (StdioMcpServerTransport transport = new StdioMcpServerTransport(server)) {

            transport.awaitClose();// keeps server running untill mcp client disconnects
        }
    }
   
}
