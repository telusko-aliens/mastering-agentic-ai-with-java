package com.telusko.langchainspring1.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import org.springframework.stereotype.Component;

@Component
public class InjectionGuardrail implements InputGuardrail
{
    private static final String[] SUSPICIOUS = {
            "ignore previous instructions",
            "ignore all previous",
            "you are now",
            "reveal your system prompt",
            "forget your rules"
    };
    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {

        // Get the actual user message
        String text = userMessage.singleText().toLowerCase();

        // Check for suspicious phrases
        for (String phrase : SUSPICIOUS) {

            if (text.contains(phrase)) {

                // Block the request.
                // LLM will NOT be called.
                return fatal(
                        "That request looks like a prompt injection attempt."
                );
            }
        }

        // Input is safe → continue to the LLM
        return success();
    }

}
