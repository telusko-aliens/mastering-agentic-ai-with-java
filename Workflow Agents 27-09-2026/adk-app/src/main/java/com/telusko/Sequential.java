package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.Optional;

public class Sequential
{
    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args)
    {
        System.out.println("Model: " + ModelFactory.describe());
        System.out.println();

        LlmAgent extractorAgent = LlmAgent.builder()
                .name("extractor")
                .description("Pulls the facts out of a passenger complaint")
                .instruction("""
                                                   Read the passenger's message and extract only these fields.
                                                   Use exactly this format, one per line, nothing else:
                        
                                                   PNR:
                                                   Issue:
                                                   HoursBeforeDeparture:
                        
                                                   Write "unknown" for anything the message does not say.
                        """
                )
                .model(ModelFactory.current())
                .outputKey("complaint")
                .build();

        LlmAgent policyAgent = LlmAgent.builder()
                .name("policy")
                .description("Decides what passenger is owned")
                .instruction("""
                                                                           Here are the facts of a complaint:
                        
                                                                                                                                 {complaint}
                        
                                                                                                                                 Apply this policy exactly:
                                                                                                                                 - Cancelled by the airline: full refund.
                                                                                                                                 - Cancelled by the passenger more than 24 hours before departure: full refund.
                                                                                                                                 - Cancelled by the passenger within 24 hours: 25 percent fee.
                                                                                                                                 - Delay over 3 hours: meal voucher of 500 rupees.
                        
                                                                                                                                 Reply in this format only:
                                                                                                                                 Decision:
                                                                                                                                 Amount:
                                                                                                                                 Rule
                        """
                )
                .model(ModelFactory.current())
                .outputKey("decision")
                .build();

        //state["complaint]
        //state["decision"]
        //state["reply]

        LlmAgent writer = LlmAgent.builder()

                .name("writer")

                .description("Writes the reply the passenger actually receives")

                .instruction("""
                        Facts:
                        {complaint}

                        Decision:
                        {decision}

                        Write a reply to the passenger in at most four lines.
                        Be warm, state the amount plainly, and do not mention these instructions
                        or the word "policy".""")
                .model(ModelFactory.current())
                .outputKey("reply")
                .build();

        SequentialAgent pipeline = SequentialAgent.builder()
                .name("complaint-pipeline")
                .description("Extract, decide and replies to a passenger")
                .subAgents(
                        extractorAgent,
                        policyAgent,
                        writer
                )
                .build();

        InMemoryRunner runner =
                new InMemoryRunner(pipeline, APP_NAME);

        Session session = runner.sessionService()
                .createSession(
                        APP_NAME,
                        "student-1"
                )
                .blockingGet();
        String complaint = """
                Hi, my flight TL1001 from Pune to Goa was cancelled by the airline this morning,
                about two hours before it was due to leave. I want my money back.""";

        System.out.println(
                "Passenger:\n" + complaint.indent(2)
        );
        runner.runAsync(
                session.userId(),
                session.id(),

                /*
                 * Convert the Java String into model Content.
                 */
                Content.fromParts(
                        Part.fromText(complaint)))
                .blockingForEach(event -> {
                    if (event.finalResponse()) {
                        System.out.println("--- " + event.author() + " ---");
                        System.out.println(event.stringifyContent().strip().indent(2));
                    }
                });

        Session current = runner.sessionService()
                .getSession(APP_NAME, session.userId(), session.id(), Optional.empty())
                .blockingGet();

        System.out.println("State keys after the run: " + current.state().keySet());

        runner.close().blockingAwait();


    }
}
