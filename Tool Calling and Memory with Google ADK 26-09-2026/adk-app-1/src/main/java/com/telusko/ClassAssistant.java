package com.telusko;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;

public class ClassAssistant
{
    public static final BaseAgent ROOT_AGENT = LlmAgent.builder()
            .name("class-assistant")
            .description("Answers questions about the Agentic AI with Java class")
            .instruction("""
                    You are the assistant for a weekend Java training class on Google ADK.

                    Answer in at most four sentences.
                    If a question is about code, give the Java answer, not the Python one.
                    If you are not sure, say so rather than inventing an API name.""")
            .model(ModelFactory.current())
            .build();

    private ClassAssistant() {
    }
}
