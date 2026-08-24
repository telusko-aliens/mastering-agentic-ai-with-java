package com.telusko.langchainspring1.web;

import com.telusko.langchainspring1.lc4j.Assitant;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController
{
    private Assitant assitant;

    public ChatController(Assitant assitant)
    {
        this.assitant=assitant;
    }
    @GetMapping("/ask")
    public String ask(@RequestParam("question") String question)
    {
        return assitant.chat(question);
    }

    @PostMapping("/askWithMemory")
    public ChatResponse askMore(@RequestBody ChatRequest request)
    {
        String response=assitant.chat(request.userId(), request.message());
//        return response;
        return new ChatResponse(request.userId(), response);
    }

    @PostMapping("/reviewCode")
    public String reviewCode(@RequestBody ReviewCode reviewCode)
    {
        return assitant.reviewCode(reviewCode.language(), reviewCode.level(), reviewCode.code());
    }

    public record ChatRequest(String userId, String message){

    }
    public record ChatResponse(String userId, String response){

    }
    public record ReviewCode(String language, String level, String code){}

}
