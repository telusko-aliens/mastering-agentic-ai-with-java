package com.telusko.quanspringapp.web;

import com.telusko.quanspringapp.service.BenchmarkService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LabController
{
    private final BenchmarkService benchmarkService;
    public LabController(BenchmarkService benchmarkService)
    {
        this.benchmarkService=benchmarkService;
    }
    @GetMapping("/variants")
    public List<Map<String, String>> variants() {
        return benchmarkService.quants().stream()
                .map(q -> Map.of("quant", q, "model", benchmarkService.modelName(q)))
                .toList();
    }

    @GetMapping("/questions")
    public List<Map<String, Object>> questions() {
        return com.telusko.quanspringapp.quiz.Quiz.QUESTIONS.stream()
                .map(q -> Map.<String, Object>of(
                        "id", q.id(),
                        "prompt", q.prompt(),
                        "expected", q.expected(),
                        "hard", q.hard()))
                .toList();
    }

    @PostMapping("/run/{quant}")
    public BenchmarkService.Result run(@PathVariable String quant) {
        return benchmarkService.run(quant);
    }
    //ollama pull hf.co/bartowski/Llama-3.2-1B-Instruct-GGUF:Q4_K_M
    //ollama pull hf.co/bartowski/Llama-3.2-1B-Instruct-GGUF:Q8_0
    //ollama pull hf.co/bartowski/Llama-3.2-1B-Instruct-GGUF:f16
}
