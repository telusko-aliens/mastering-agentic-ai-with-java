package com.telusko.langchainspring1.lc4j;

import com.telusko.langchainspring1.guardrail.InjectionGuardrail;
import com.telusko.langchainspring1.guardrail.PiiIInputguardrail;
import com.telusko.langchainspring1.guardrail.PiiOutputGuardrail;
import com.telusko.langchainspring1.guardrail.ShortAnswerGuarrail;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.InputGuardrails;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import dev.langchain4j.service.spring.AiService;

@AiService
//@InputGuardrails({
//        InjectionGuardrail.class
//        , PiiIInputguardrail.class})
@InputGuardrails(
        InjectionGuardrail.class
        )
@OutputGuardrails({ShortAnswerGuarrail.class
,PiiOutputGuardrail .class})
public interface TeluskoAssistant
{
    @SystemMessage("""
            You are Telusko Bot,
            an assistant for Java , Spring Boot and AI.

            Answer questions about programming.

           .
            """)
    String chat(@UserMessage String message);
}
