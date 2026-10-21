package com.telusko.springai2.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.ChoiceQuestion;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.request.ScaleQuestion;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.ScaleAnswer;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiDecisionModel;

@RestController
@RequestMapping("/api")
public class TriageController 
{
	
	 private static final String SAMPLE =
	            "Help! My payouts have been failing for 3 days and nobody answers my emails. "
	                    + "I have customers waiting and I am seriously considering cancelling.";
	
	    private final DecisionModel decisions;
	    private final ChatModel chat;
	    
	    public TriageController(@Value("${OPENAI_API_KEY}")String apiKey)
	    {
	    		this.decisions=OpenAiDecisionModel.builder()
	    				.apiKey(apiKey)
	    				.modelName("gpt-6-luna")
	    				.build();
	    		this.chat= OpenAiChatModel.builder()
	    				.apiKey(apiKey)
	    				.modelName("gpt-4.1-mini")
	    				.build();
	    	
	    }
	    
	    //OpenAiDecisionModel --> creates the Decision Model using OpenAI
	    
	    public Map<String, Object>triage(@RequestParam(defaultValue = SAMPLE)String text)
	    {
	        long started = System.currentTimeMillis();
	        
	        //DecisionRequest request --> sends the customer complaiant along with three quesiton

	        DecisionRequest request = DecisionRequest.builder()
	                .input(text)

	                // Yes or no. The answer carries the probability of "yes", not just the word.
	                .question("urgent", YesNoQuestion.of("Does this need attention today?"))

	                // Exactly one option out of several. Every option gets a probability.
	                .question("team", ChoiceQuestion.of(
	                        "Which team should handle this?",
	                        List.of("payments", "accounts", "technical", "sales")))

	                // An ordered scale. Levels go from lowest to highest, and the answer can
	                // land between two of them.
	                .question("frustration", ScaleQuestion.of(
	                        "How frustrated is the customer?",
	                        List.of("calm", "annoyed", "angry", "about to leave")))

	                .build();
	        
	        //YesNoQuestion --> checks weather the complaint is urgent
	        //ChoiceQuestion --> Selects approriate team
	        //ScaleQuestion  --> measure customer frustration

	        DecisionResponse response = decisions.decide(request);

	        YesNoAnswer urgent = response.yesNo("urgent");
	        ChoiceAnswer team = response.choice("team");
	        ScaleAnswer frustration = response.scale("frustration");

	        Map<String, Object> out = new LinkedHashMap<>();
	        out.put("model", response.modelName());
	        out.put("seconds", seconds(started));

	        // Every answer is a Java type. No parsing, no regex, no "extract the JSON".
	        out.put("urgent", Map.of(
	                "answer", urgent.isYes(0.5),// return true if probability of Yes exceeds 50%
	                "probability", round(urgent.probability())));

	        out.put("team", Map.of(
	                "answer", team.value(),
	                "probabilities", round(team.probabilities()),
	                "margin", round(team.margin())));

	        out.put("frustration", Map.of(
	                "mean", round(frustration.mean()),
	                "levels", List.of("calm", "annoyed", "angry", "about to leave"),
	                "probabilities", frustration.probabilities().stream().map(TriageController::round).toList()));

	        out.put("tokens", String.valueOf(response.tokenUsage()));
	        return out;

	    }
	    
	    
	    
	    
	    
	    
	    @GetMapping("/chat")
	    public Map<String, Object> viaChat(@RequestParam(defaultValue = SAMPLE) String text) {

	        long started = System.currentTimeMillis();

	        String answer = chat.chat("""
	                Read this support ticket and answer three things.

	                1. Does it need attention today? yes or no.
	                2. Which team should handle it: payments, accounts, technical or sales?
	                3. How frustrated is the customer: calm, annoyed, angry or about to leave?

	                Ticket:
	                %s""".formatted(text));

	        Map<String, Object> out = new LinkedHashMap<>();
	        out.put("model", "gpt-4.1-mini");
	        out.put("seconds", seconds(started));
	        out.put("answer", answer);
	        out.put("note", "Text. Your code still has to read it, and there are no probabilities.");
	        return out;
	    }
	    
	    @GetMapping("/compare")
	    public Map<String, Object> compare(@RequestParam(defaultValue = SAMPLE) String text) {

	        var decision = CompletableFuture.supplyAsync(() -> triage(text));
	        var prose = CompletableFuture.supplyAsync(() -> viaChat(text));

	        return Map.of("ticket", text,
	                "decisionModel", decision.join(),
	                "chatModel", prose.join());
	    }

	    
	    private static double seconds(long started) {
	        return (System.currentTimeMillis() - started) / 1000.0;
	    }

	    /** Two decimal places, because 0.9437281 helps nobody read a table. */
	    private static double round(double value) {
	        return Math.round(value * 100) / 100.0;
	    }

	    private static Map<String, Double> round(Map<String, Double> values) {
	        Map<String, Double> out = new LinkedHashMap<>();
	        values.forEach((key, value) -> out.put(key, round(value)));
	        return out;
	    }
	    
	    
	    
	    
	    
	    
	    
	    
	    

}
