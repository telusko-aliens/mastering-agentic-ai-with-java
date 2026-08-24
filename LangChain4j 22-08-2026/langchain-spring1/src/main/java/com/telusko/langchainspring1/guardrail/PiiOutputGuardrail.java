package com.telusko.langchainspring1.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class PiiOutputGuardrail implements OutputGuardrail
{
    private static final Pattern EMAIL =
            Pattern.compile(
                    "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
                    Pattern.CASE_INSENSITIVE
            );

    private static final Pattern PHONE =
            Pattern.compile(
                    "\\b(?:\\+91[- ]?)?[6-9]\\d{9}\\b"
            );
    @Override
    public OutputGuardrailResult validate(AiMessage response) {

        String text = response.text();
        String maskedText =
                EMAIL.matcher(text).replaceAll("***");
        maskedText =
                PHONE.matcher(maskedText).replaceAll("***");

        if (!maskedText.equals(text)) {

            return successWith(maskedText);
        }
        return success();
    }

}
