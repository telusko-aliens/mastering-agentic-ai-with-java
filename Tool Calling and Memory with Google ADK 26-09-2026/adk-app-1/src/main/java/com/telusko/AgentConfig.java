package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;

public class AgentConfig
{
    private static final String APP_NAME = "telusko-adk";
    public static void main(String[] args)
    {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .temperature(0.8f)
                .maxOutputTokens(400)
                .build();

        LlmAgent teluskoAgent = LlmAgent.builder()
                .name("telusko-cafe")
                .description("Suggest me names of coffee shops")
                .instruction("Suggest one creative coffee shop name. Keep the answer short")
                .model(ModelFactory.current())
                .generateContentConfig(config)
                .build();

        InMemoryRunner runner = new InMemoryRunner(teluskoAgent, APP_NAME);

        Session session = runner.sessionService() .createSession(APP_NAME, "student-1") .blockingGet();

        Content question = Content.fromParts( Part.fromText("Suggest a name for my new coffee shop.") );

        runner.runAsync(
                session.userId(),
                session.id(),
                question )
                .blockingForEach(event ->
                 {
            // Print only the final asnwer
                 if (event.finalResponse())
                {
                     System.out.println( "Agent: " + event.stringifyContent() );
                 }
                });

        runner.close().blockingAwait();

    }
}
