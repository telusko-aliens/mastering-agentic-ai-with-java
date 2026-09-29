package com.telusko;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

public class First_toolAgent
{
    private static final String APP_NAME = "telusko-adk";

    public static void main(String[] args)
    {
        System.out.println(
                "Model: "
                        + ModelFactory.describe()
        );

        System.out.println();

        LlmAgent agent = LlmAgent.builder()
                .name("booking-agent")
                .description("Answers questions about flight bookings")
                .instruction(
                        """
                                                               You are a support agent for an airline.
                                
                                                               Use getBooking to look up a booking before answering anything about it.
                                                               Use cancellationFee when the passenger asks about cancelling.
                                
                                                               Never invent a PNR, a fare or a status. If a tool says the booking was
                                                               not found, tell the passenger that and ask them to check the code. 
                                """
                )
                .model(ModelFactory.current())
                .tools(
                        FunctionTool.create(
                                BookingTools.class,
                                "getBooking"
                        ),
                        FunctionTool.create(
                                BookingTools.class,
                                "cancellationFee"
                        )

                )
                .build();
        InMemoryRunner runner = new InMemoryRunner(agent,APP_NAME);
        Session session = runner.sessionService().createSession(APP_NAME, "cx-1").blockingGet();

//        ask(
//                runner,
//                session,
//                "What is the status of booking TL1001?");

//        ask(
//                runner,
//                session,
//                "I want to cancel TL1001, there are 6 hours before departure. "
//                        + "What will I get back?"
//        );

//        ask(
//                runner,
//                session,
//                "Tell me about booking TL9999."
//        );
        ask(
                runner,
                session,
                "what is googleadk for java and how mature it's."
        );


        runner.close()
                .blockingAwait();

    }

    private static void ask(InMemoryRunner runner, Session session, String question) {

        System.out.println("=== " + question);

        runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(question)))
                .blockingForEach(First_toolAgent::show);

        System.out.println();
    }

    private static void show(Event event) {

        // The model asking for a tool. functionCalls() is empty on a plain text reply.
        event.functionCalls().forEach(call ->
                System.out.println("  [tool call]     " + call.name().orElse("?")
                        + " " + call.args().orElse(java.util.Map.of())));

        // Our method's return value, on its way back to the model.
        event.functionResponses().forEach(response ->
                System.out.println("  [tool result]   " + response.response().orElse(java.util.Map.of())));

        if (event.finalResponse()) {
            System.out.println("  [answer]        " + event.stringifyContent().strip());
        }
    }
}
