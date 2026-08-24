package com.telusko.langchainspring1.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class PiiIInputguardrail implements InputGuardrail
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
    public InputGuardrailResult validate(UserMessage userMessage) {

        String text = userMessage.singleText();
        if(EMAIL.matcher(text).find())
        {
            return fatal("PII detected: email address is not allowed");

        }
        if(PHONE.matcher(text).find())
        {
            return fatal("PII detected: phone number is not allowed");
        }
       return success();
    }


}
