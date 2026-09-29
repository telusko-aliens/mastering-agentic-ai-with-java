package com.telusko;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StdioServerParameters;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class MCPToolCall
{
    //ADK Agent --> McpToolSet --> MCP Server (tools)

    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args) throws Exception
    {
        System.out.println("Model: " + ModelFactory.describe());

        Path sandbox = Path.of(System.getProperty("java.io.tmpdir"), "adk-mcp-demo");
        Files.createDirectories(sandbox);
        Files.writeString(sandbox.resolve("flight-notes.txt"), """
                Flight TL1001 PUN to GOA departs 07:15 on 2 October 2026.
                Passenger Shramik, seat 12A, checked baggage 15 kg.
                Note: the Goa runway closes for maintenance between 02:00 and 05:00.""");

        System.out.println("Sandbox: " + sandbox);
        System.out.println();

        //---------------------------------------------------------------------//

        String npx = System.getProperty("os.name").toLowerCase().contains("win") ? "npx.cmd" : "npx";

        StdioServerParameters serverParams = StdioServerParameters.builder()
                .command(npx)
                .args(List.of(
                                        "-y",
                                        "@modelcontextprotocol/server-filesystem",
                                        sandbox.toString())
                        ).build();

        try (
                McpToolset toolset = new McpToolset(serverParams.toServerParameters(),new ObjectMapper())
        ){
            // MCP discovery --> what tools do you provide
            List<BaseTool> mcpTools = toolset.getTools(null).toList().blockingGet();
            System.out.println("Tools published by the MCP server: " + mcpTools.size());
           mcpTools.forEach(tool -> System.out.println(" - "+ tool.name()));
            LlmAgent agent = LlmAgent.builder()
                    .name("file-agent")
                    .description("Answer questions using files in a folder")
                    .instruction("""
                            You can read files in a folder using the tools you have been given.
                            
                            List the folder first if you do not know the file name.
                            Answer only from what the files actually say.
                            Keep answers to three lines.""")
                    .model(ModelFactory.current())
                    .tools(mcpTools)
                    .build();


            InMemoryRunner runner = new InMemoryRunner(agent, APP_NAME);
            Session session = runner.sessionService()
                    .createSession(APP_NAME, "student-1").blockingGet();

//            ask(runner, session, "What files are in the folder?");
            ask(runner, session, "When does flight TL1001 depart, and what is the baggage allowance?");

            // ADK --> McpToolset --> Mcp client --> MCP server --> tool executes --> ADK --> LLM --> user


            runner.close().blockingAwait();;
        }
    }
   // Google ADK agent ---> McpToolset --> MCP Client -->MCP server


    private static void ask(InMemoryRunner runner, Session session, String question) {

        System.out.println("=== " + question);

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(MCPToolCall::show);

        System.out.println();
    }

    private static void show(Event event) {

        event.functionCalls().forEach(call ->
                System.out.println("  [mcp call]      " + call.name().orElse("?")
                        + " " + call.args().orElse(Map.of())));

        if (event.finalResponse()) {
            System.out.println("  [answer]        " + event.stringifyContent().strip());
        }
    }

}
