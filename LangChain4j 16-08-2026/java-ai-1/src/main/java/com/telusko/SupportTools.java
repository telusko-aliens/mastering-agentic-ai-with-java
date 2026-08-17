package com.telusko;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public class SupportTools
{
    public record Enrolment(
            String studentName,
            String course,
            LocalDate enrolledOn,
            int amountPaid
    ) { }
    private static Map<String, Enrolment> enrolments=Map.of
            (
            "shramik@gmail.com",
            new Enrolment("Shramik",
                    "Agentic AI with Java",
                    LocalDate.of(2026, 8, 15), 5000)

            ,
   "harsh@gmail.com",
           new Enrolment("Harsh",
                                 "Agentic AI with Python",
                         LocalDate.of(2026, 8, 8), 5000)
        ) ;
    @Tool("Look up a student's enrolment record using their email address")
    Enrolment findEnrolment(@P("the student's email address") String email)
    {
        return enrolments.get(email.toLowerCase().trim());
    }
    @Tool("Returns today's date")
    LocalDate today() {

        System.out.println("[tool] today()");

        return LocalDate.now();
    }
    @Tool("Checks whether a refund is still allowed, given the enrolment date and today's date")
    String checkRefund(
            @P("the date the student enrolled, as yyyy-MM-dd")
            LocalDate enrolledOn,

            @P("today's date, as yyyy-MM-dd")
            LocalDate today
    ) {

        System.out.println(
                "[tool] checkRefund(" + enrolledOn + ", " + today + ")"
        );

        // Calculate how many days have passed since enrolment.
        long days = ChronoUnit.DAYS.between(enrolledOn, today);

        // Apply the actual 7-day refund business rule.
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
