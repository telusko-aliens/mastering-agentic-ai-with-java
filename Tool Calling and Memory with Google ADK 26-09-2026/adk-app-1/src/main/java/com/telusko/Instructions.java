package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

public class Instructions
{
    private static final String APP_NAME = "telusko-adk";

    private static final String QUESTION = "A customer says their order has not arrived yet.";

    public static void main(String[] args) {


        // agent creation
        LlmAgent vagueAgent = LlmAgent.builder()
                .name("vague")
                .description("Handles customer support questions")
                .instruction("You are a helpful customer support assistant.")
                .model(ModelFactory.current())
                .build();

        LlmAgent preciseAgent = LlmAgent.builder()
                .name("precise")
                .description("Handles customer support questions")
                .instruction(
                        """ 
                                 You are a support agent for an online electronics store. Answer in exactly this shape:
                                 Acknowledge: <one sentence showing you understood> 
                                Next step: <the single action you will take> 
                                Ask: <the one piece of information you need from the customer> 
                                Rules: - Never promise a refund, a delivery date or compensation. -
                                If the question is not about an order, say it is outside your scope. -
                                 Never write more than three lines.
                                 """
                )
                .model(ModelFactory.current())
                .build();

        ask("VAGUE Instruction", vagueAgent);
        ask("PRECISE Instruction ", preciseAgent);
    }

        private static void ask (String label, LlmAgent agent){
            InMemoryRunner runner = new InMemoryRunner(agent, APP_NAME);

            Session session = runner.sessionService()
                    .createSession(APP_NAME, "student-1")
                    .blockingGet();

            System.out.println("=== " + label + " ===");

            runner.runAsync(session.userId(),
                            session.id(),
                            Content.fromParts(Part.fromText(QUESTION)))
                    .blockingForEach(event -> {
                        if (event.finalResponse()) {
                            System.out.println(event.stringifyContent());
                        }
                    });

            System.out.println();
            runner.close().blockingAwait();
        }


}
