package com.telusko;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static java.rmi.server.LogStream.log;

public class SupportTools
{
    public record Enrolment(
            String studentName,
            String course,
            LocalDate enrolledOn,
            int amountPaid) {
    }
    private static final Map<String, Enrolment> ENROLMENTS = Map.of(

            "ramesh@example.com",
            new Enrolment(
                    "Ramesh Kumar",
                    "Agentic AI with Java",
                    LocalDate.of(2026, 8, 17),
                    14999),

            "priya@example.com",
            new Enrolment(
                    "Priya Sharma",
                    "Agentic AI with Java",
                    LocalDate.of(2026, 6, 20),
                    14999)
    );

    @Tool("Looks up a student's enrolment record using their email address")
    Enrolment findEnrolment(
            @P("the student's email address") String email) {

        log("findEnrolment(" + email + ")");

        return ENROLMENTS.get(email.toLowerCase().trim());
    }

    @Tool("Returns today's date")
    LocalDate today() {

        log("today()");

        return LocalDate.now();
    }

    @Tool("Checks whether a refund is still allowed, given the enrolment date and today's date")
    String checkRefund(
            @P("the date the student enrolled, as yyyy-MM-dd") String enrolledOn,
            @P("today's date, as yyyy-MM-dd") String today) {

        log("checkRefund(" + enrolledOn + ", " + today + ")");

        // Convert String dates into LocalDate for calculation
        long days = ChronoUnit.DAYS.between(
                LocalDate.parse(enrolledOn),
                LocalDate.parse(today));

        // Refund is allowed within 7 days
        if (days <= 7) {
            return "Refund allowed. "
                    + (7 - days)
                    + " day(s) left in the 7 day window.";
        }

        return "Refund window closed. The student enrolled "
                + days + " days ago.";
    }

    //logging method
    private static void log(String message) {
        System.err.println("[server] " + message);
    }

}
