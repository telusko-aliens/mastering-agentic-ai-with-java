package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import javax.swing.text.AbstractDocument;

public class HelloAgent {

    private static final String APP_NAME="telusko-adk";
    public static void main(String[] args) {
        System.out.println("Model: " + ModelFactory.describe());
        System.out.println();

        // agent creation
        LlmAgent agent = LlmAgent.builder()
                .name("greeter")
                .description("Answers general questions in  simple plain language")
                .instruction(
                        """
                                 You are a helpful assistant for a Java training class.
                                 Answer in at most three sentences.
                                 If you do not know something, say No instead of guessing. 
                                """
                )
                .model(ModelFactory.current())
                .build();

        //Agent --> What to do
        // Runner --> actually to run agent
        // Create runner --> (Runner is responsible to execute the agent)

        InMemoryRunner runner = new InMemoryRunner(agent, APP_NAME);

        // session --> represents one conversation
        Session session = runner.sessionService()
                .createSession(APP_NAME, "telusko-1")
                .blockingGet();

        Content question = Content.fromParts(Part.fromText(
                "Explain what is Google ADK for Java app in short"
        ));

        // running our agent


       runner.runAsync(session.userId(), session.id(), question)
               .blockingForEach(event -> {
                   if (event.finalResponse()) {
                       System.out.println("Agent: " + event.stringifyContent());
                   }
               })
               ;

//        private static String ask(Session session, String question)
//        {
//            StringBuilder answer = new StringBuilder();
//            runner.runAsync(
//                            session.userId(), session.id(), question)
//                    .blockingForEach(event ->
//                    {
//                        if (event.finalResponse()) {
//                            answer.append(event.stringifyContent().strip());
//                        }
//                    });
//            return answer.toString();
//        }


        runner.close().blockingAwait();
    }
    }



}
