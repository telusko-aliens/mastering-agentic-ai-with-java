package com.telusko.agenticaisystem.web;

import com.telusko.agenticaisystem.agents.Review;
import com.telusko.agenticaisystem.service.AgenticService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequestMapping("/agentic/api")
@RestController
public class AgenticController
{
    @Autowired
    private AgenticService service;

    @GetMapping("/basic")
    public String basicAgentApi(@RequestParam String topic)
    {
        return service.basicAgent(topic);
    }

    @GetMapping("/sequential")
    public String seqAgentApi(@RequestParam String topic,
                              @RequestParam(defaultValue = " a general audience") String audience)
    {
        return service.sequential(topic, audience);
    }

    @GetMapping("/loop")
    public String loopAgentApi(@RequestParam String story)
    {
        return service.loop(story);
    }

    @GetMapping("/parallel")
    public Review parallelAgentApi(@RequestParam String text)
    {
        return service.parallelAgents(text);
    }

    @GetMapping("/mapper")
    public Object parallelAgentApi(@RequestParam List<String> topics)
    {
        return service.mapper(topics);
    }

    @GetMapping("/conditional")
    public String conditionalAgentApi(@RequestParam String message)
    {
        String res=service.condionalAgents(message);
        System.out.println(res);
        return res;
    }
}
