package com.telusko.springai2.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.telusko.springai2.tools.CourseTools;

@RestController
@RequestMapping("/api")
public class ChatController 
{
    private static final String CONVERSATION = "class";

    private final ChatClient chat;
    private final ChatModel model;
    private final ChatMemory memory;
    private final CourseTools tools;
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final ToolCallingManager toolCallingManager;
    
    public ChatController(ChatClient.Builder builder, ChatModel model, CourseTools tools, ToolCallingManager toolCallingManager) {
        this.model = model;
        this.tools = tools;

        this.memory = MessageWindowChatMemory.builder()
        		
                .maxMessages(20)
                // IN CLASS (1.1.8): the advisor could be given a conversation id here,
                //                   and everything without one fell into a shared
                //                   default conversation.
                // NOW (2.0):        .conversationId() is gone from the builder, and so
                //                   is ChatMemory.DEFAULT_CONVERSATION_ID. The id is
                //                   supplied per call instead. See chat() below.
                .build();

        // The memory advisor is NOT a default advisor here, and that is deliberate.
        //
        // IN CLASS (1.1.8): putting it in defaultAdvisors was the normal thing to do.
        //                   Calls that never mentioned a conversation still worked;
        //                   they silently shared DEFAULT_CONVERSATION_ID.
        // NOW (2.0):        a default memory advisor means EVERY call must carry an id.
        //                   The first call that forgets fails with:
        //
        //                       IllegalArgumentException: conversationId cannot be null
        //
        //                   So memory is attached per endpoint instead. Only /api/chat
        //                   remembers anything, which is also more honest.
        this.chat = builder
                .defaultSystem("""
                        You are a Java teaching assistant for the Telusko course.
                        Answer in plain English, in at most four sentences.
                        No markdown.""")
                .build();

        this.memoryAdvisor = MessageChatMemoryAdvisor.builder(memory).build();
        this.toolCallingManager=toolCallingManager;
    }
    
    @GetMapping("/ask")
    public String ask(@RequestParam(defaultValue = "What is Spring Boot?") String q) {
        return chat.prompt()
                .user(q)
                .call()
                .content();
    }
    
    @GetMapping("/tools")
    public Map<String, Object> withTools(
            @RequestParam(defaultValue = "How many seats are left on spring-ai?") String q) {

        String answer = chat.prompt()
                .user(q)
                // IN CLASS (1.1.8): .functions("seatsLeft")  or  .tools(tools)
                //                   The bean-name form resolved a @Bean Function by name.
                // NOW (2.0):        functions(...) is gone, renamed to tools(...).
                //                   Bean-name resolution is gone too: no toolNames(),
                //                   no SpringBeanToolCallbackResolver. Pass the object
                //                   that holds the @Tool methods, or a ToolCallback.
                .tools(tools)
                .call()
                .content();

        return Map.of("answer", answer,
                "whoRanTheLoop", "ToolCallingAdvisor, registered by ChatClient");
    }
    
    @GetMapping("/raw")
    public Map<String, Object> raw(
            @RequestParam(defaultValue = "How many seats are left on spring-ai?") String q) {

        // IN CLASS (1.1.8): this returned the final text.
        // NOW (2.0):        it returns the first response and stops. Tool calls are not
        //                   executed. internalToolExecutionEnabled is gone as well, so
        //                   there is no flag to switch the old behaviour back on.
        var response = model.call(new Prompt(q,
                OpenAiChatOptions.builder()
                        .toolCallbacks(org.springframework.ai.support.ToolCallbacks.from(tools))
                        .build()));

        var output = response.getResult().getOutput();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", output.getText() == null ? "" : output.getText());
        out.put("toolCallsRequested", output.getToolCalls().stream()
                .map(call -> call.name() + " " + call.arguments())
                .toList());
        out.put("note", output.getToolCalls().isEmpty()
                ? "No tool was needed for this question."
                : "ChatModel stopped here. In 1.1.8 it would have run these and looped.");
        return out;
    }
    
    @GetMapping("/cm-final")
    public String getChatModelFinalresponse(@RequestParam(defaultValue = "How many seats are left on spring-ai?") String q)
    {
    		OpenAiChatOptions options = OpenAiChatOptions.builder()
    		.toolCallbacks(ToolCallbacks.from(tools))
    		.build();
    	
    		Prompt prompt = new Prompt(q, options);
    		ChatResponse response = model.call(prompt);
    		if(response.hasToolCalls())
    		{
    			ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
    			Prompt newPrompt=new Prompt(result.conversationHistory(),options);
    			response=model.call(newPrompt);
    		}
    		String answer=response.getResult().getOutput().getText();
    		return answer;
    }
    
    //Use ChatClient for most application-level AI interactions, particularly when you want advisors and automatic tool calling.
    
    //Use ChatModel when you need lower-level control, such as inspecting raw model responses or implementing your own tool-execution workflow.
    
    @PostMapping("/chat")
    public String chat(@RequestBody String message) {
        return chat.prompt()
                .user(message)
                .advisors(memoryAdvisor)
                // IN CLASS (1.1.8): this line was optional. Leave it out and everything
                //                   went into ChatMemory.DEFAULT_CONVERSATION_ID, so one
                //                   user's history quietly became everyone's.
                // NOW (2.0):        the id is required. DEFAULT_CONVERSATION_ID was
                //                   removed so the ambiguous case cannot happen. Delete
                //                   this line and the call throws, it does not fall back.
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION))
                .call()
                .content();
    }
    
    @DeleteMapping("/chat")
    public void reset() {
        memory.clear(CONVERSATION);
    }
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    


}
