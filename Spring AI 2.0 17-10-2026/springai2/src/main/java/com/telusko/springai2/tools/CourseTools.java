package com.telusko.springai2.tools;

import java.time.LocalDate;
import java.util.Map;


//import org.springframework.community.mcp.annotation.McpTools

//import org.springframework.ai.mcp.annotation.McpTools

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CourseTools
{
    private static final Map<String, Integer> SEATS = Map.of(
            "spring-ai", 12,
            "langchain4j", 4,
            "google-adk", 0);

    @Tool(description = "How many seats are left on a Telusko course")
    public String seatsLeft(
            @ToolParam(description = "Course code, for example spring-ai") String course) {

        Integer seats = SEATS.get(course.toLowerCase());

        if (seats == null) {
            return "No course with that code. Try spring-ai, langchain4j or google-adk.";
        }
        return seats == 0 ? course + " is full" : course + " has " + seats + " seats left";
    }

    @Tool(description = "Today's date, so the model does not guess it")
    public String today() {
        return LocalDate.now().toString();
    }

}
