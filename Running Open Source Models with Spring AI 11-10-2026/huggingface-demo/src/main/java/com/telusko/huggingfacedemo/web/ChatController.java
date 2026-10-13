package com.telusko.huggingfacedemo.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController
{
    private static final String CONVERSATION = "class";

    private final ChatClient chat;
    private final ChatMemory memory;
    private final String modelName;
    private final boolean local;

    public ChatController(ChatClient.Builder builder, ChatModel model)
    {
        this.modelName=model.getDefaultOptions()== null ? "unknown"
                : model.getDefaultOptions().getModel();
        this.local=model.getClass().getSimpleName().startsWith("ollama");
        this.memory= MessageWindowChatMemory.builder().maxMessages(20).build();
        this.chat=builder.defaultSystem(
                """
                        You are a Java teaching assistant for the Telusko course.
                        Answer in plain English, in at most four sentences.
                        If you do not know something, say so instead of inventing it."""
        ).defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build();
    }
    @PostMapping("/chat")
    public String chat(@RequestBody String message)
    {
        System.out.println("chat springboot backend");
        return chat.prompt()
                .user(message)
                .advisors(a->a.param(ChatMemory.CONVERSATION_ID, CONVERSATION))
                .call()
                .content();
    }
    @GetMapping("/model")
    public Map<String, String> model() {
        return Map.of(
                "model", modelName,
                "runningOn", local ? "this machine" : "Hugging Face servers",
                "costPerMessage", local ? "nothing" : "your Hugging Face credits");
    }
}
