package com.telusko.langchainspring1.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import org.springframework.stereotype.Component;

@Component
public class ShortAnswerGuarrail implements OutputGuardrail
{
    private static final int MAX_CHARS=600;

    @Override
    public OutputGuardrailResult validate(AiMessage response) {

        String text = response.text();
        if(text.length() > MAX_CHARS)
        {
//            return reprompt(
//                    "Response too long.",
//                    "Answer the same question again in under "+MAX_CHARS+" characters.");
            return successWith(text.substring(0, MAX_CHARS));
        }
        if(text.toLowerCase().startsWith("i am sorry") || text.toLowerCase().startsWith("i'm sorry"))
        {
           return successWith(" I cannot help with that one. but ask me anything about java and ai");
        }
        return success();
    }
}
