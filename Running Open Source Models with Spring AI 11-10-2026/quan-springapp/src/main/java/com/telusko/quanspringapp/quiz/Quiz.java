package com.telusko.quanspringapp.quiz;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Quiz {

    /**
     * One question, and the words that make an answer right.
     *
     * @param id       short label for the results table
     * @param prompt   what the model is asked
     * @param expected correct if the reply contains any one of these
     * @param hard     true for the compute questions, false for recall
     */
    public record Question(String id, String prompt, List<String> expected, boolean hard) {

        /**
         * Correct if the reply contains any expected answer as a whole word.
         *
         * <p>A whole word and not just a substring, which matters more than it looks.
         * A plain contains() check would mark "64" correct for an expected "4", and
         * would mark the word "quantization" correct for an expected "a". Both were
         * happening before this was tightened.
         */
        public boolean isCorrect(String reply) {
            if (reply == null || reply.isBlank()) {
                return false;
            }
            String lower = reply.toLowerCase(Locale.ROOT);
            return expected.stream().anyMatch(e -> {
                String word = e.toLowerCase(Locale.ROOT);
                return Pattern.compile("\\b" + Pattern.quote(word) + "\\b")
                        .matcher(lower)
                        .find();
            });
        }
    }

    /** Short answers, or the slow variants spend the whole benchmark writing essays. */
    public static final String SYSTEM = """
            Answer in as few words as possible. No explanation, no preamble.
            If the question asks for a number, reply with only the number.""";

    public static final List<Question> QUESTIONS = List.of(

            // ---------------------------------------------------------- recall
            new Question("capital",
                    "What is the capital city of France?",
                    List.of("Paris"), false),

            new Question("final",
                    "Which Java keyword stops a variable from being reassigned?",
                    List.of("final"), false),

            new Question("extends",
                    "Which Java keyword is used to create a subclass?",
                    List.of("extends"), false),

            new Question("restcontroller",
                    "Which Spring annotation marks a class as a REST controller? "
                            + "Reply with the annotation only.",
                    List.of("RestController"), false),

            new Question("port",
                    "What is the default HTTP port of a Spring Boot web application?",
                    List.of("8080"), false),

            new Question("jvm",
                    "What does JVM stand for?",
                    List.of("Java Virtual Machine"), false),

            new Question("maven",
                    "Which Java build tool uses a file called pom.xml?",
                    List.of("Maven"), false),

            new Question("map",
                    "Which Java collection type stores key and value pairs?",
                    List.of("map", "hashmap"), false),

            // ------------------------------------------------------------ work
            new Question("multiply",
                    "What is 17 multiplied by 23? Reply with only the number.",
                    List.of("391"), true),

            new Question("sequence",
                    "A list has 142 items. 37 are removed, then 18 are added. "
                            + "How many items are in the list now? Reply with only the number.",
                    List.of("123"), true),

            new Question("percent",
                    "What is 15 percent of 240? Reply with only the number.",
                    List.of("36"), true),

            new Question("power",
                    "What is 2 to the power of 10? Reply with only the number.",
                    List.of("1024"), true),

            new Question("intbytes",
                    "How many bytes does a Java int occupy? Reply with only the number.",
                    List.of("4"), true),

            new Question("letter",
                    "Reply with only the third letter of the word quantization.",
                    List.of("a"), true),

            new Question("onecolour",
                    "Reply with exactly one word, in lower case: the colour of a clear "
                            + "midday sky.",
                    List.of("blue"), true));

    private Quiz() {
    }
}
