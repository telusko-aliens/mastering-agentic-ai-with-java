package com.telusko.langchainspring1.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Component   ///  supportTools
public class SupportTools
{
    public record Enrolment(
            String studentName,
            String course,
            LocalDate enrolledOn,
            int amoundPaid
    )
    {}
    private static final Map<String, Enrolment> ENROLMENTS = Map.of(
            "ramesh@example.com",
            new Enrolment(
                    "Ramesh Kumar",
                    "Agentic AI with Java",
                    LocalDate.of(2026, 8, 12),
                    14999
            ),

            "priya@example.com",
            new Enrolment(
                    "Priya Sharma",
                    "Agentic AI with Java",
                    LocalDate.of(2026, 6, 20),
                    14999
            )
    );
    @Tool("Looks up a student's enrolment record using their email address")
    Enrolment findEnrolment(
            @P("the student's email address") String email
    ) {
        return ENROLMENTS.get(email.toLowerCase().trim());
    }
    @Tool("Returns today's date")
    LocalDate today() {
        return LocalDate.now();
    }
    @Tool("Checks whether a refund is still allowed, given the enrolment date and today's date")
    String checkRefund(
            @P("the date the student enrolled, as yyyy-MM-dd")
            LocalDate enrolledOn,

            @P("today's date, as yyyy-MM-dd")
            LocalDate today
    ) {

        // Calculate how many days have passed since enrolment.
        long days = ChronoUnit.DAYS.between(enrolledOn, today);

        // Apply the refund rule.
        if (days <= 7) {
            return "Refund allowed. "
                    + (7 - days)
                    + " day(s) left in the 7 day window.";
        }

        return "Refund window closed. "
                + "The student enrolled "
                + days
                + " days ago.";
    }

}
