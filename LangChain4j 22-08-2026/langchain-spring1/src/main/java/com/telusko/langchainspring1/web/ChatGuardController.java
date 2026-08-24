package com.telusko.langchainspring1.web;

import com.telusko.langchainspring1.lc4j.TeluskoAssistant;
import dev.langchain4j.guardrail.InputGuardrailException;
import dev.langchain4j.guardrail.OutputGuardrailException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequestMapping("/api/guard")
@RestController
public class ChatGuardController {
    @Autowired
    private TeluskoAssistant assistant;

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request)
    {
        String message= request.message();
        if(message == null || message.isBlank())
        {
            return ResponseEntity.badRequest()
                    .body(Map.of("blocked", true, "reason", "Please type a question"));
        }
        String answer=assistant.chat(message);
        return ResponseEntity.ok(new ChatResponse(answer));

    }
    @ExceptionHandler(InputGuardrailException.class)
    public ResponseEntity<Map<String, String>> onInputBlocked(
            InputGuardrailException e) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "blocked", "true",
                        "reason", e.getMessage()
                ));
    }

    @ExceptionHandler(OutputGuardrailException.class)
    public ResponseEntity<Map<String, String>> onOutputBlocked(
            OutputGuardrailException e) {

        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "error",
                        "The model could not produce an acceptable answer.",
                        "reason",
                        String.valueOf(e.getMessage())
                ));
    }

    // Request body
    public record ChatRequest(String message) {
    }

    // Response body
    public record ChatResponse(String answer) {
    }

}
