package com.telusko.agenticaisystem.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public class Agents
{
    private Agents()
    {
    }
    public interface StoryWriter
    {
        @UserMessage("Write a short, vivid story about {{topic}}")
        @Agent("Write a short story from a topic")
        String write(@V("topic")String topic);
    }

    public interface AudienceEditor {
        @UserMessage("""
                Rewrite the story below so it lands well with this audience: {{audience}}.
                Keep about the same length. Story: {{story}}
                """)
        @Agent("Rewrites the story for a target audience")
        String edit(@V("story") String story, @V("audience") String audience);
    }

    public interface StyleEditor {
        @UserMessage("Polish the language of this story, keep the plot, return only the story: {{story}}")
        @Agent("Polishes the writing style")
        String polish(@V("story") String story);
    }
    public interface StyleScorer {
        @UserMessage(
                """
        Evaluate the writing quality of the following story.

        Give a score from 0.0 to 1.0 using this rubric:

        0.0 - 0.2 : Very poor writing. Major problems with grammar,
                    clarity, structure, or readability.

        0.2 - 0.4 : Basic writing. Understandable but has several
                    weaknesses in grammar, clarity, detail, or flow.

        0.4 - 0.6 : Average writing. Mostly clear with some
                    noticeable issues.

        0.6 - 0.8 : Good writing. Clear, readable, well-structured,
                    with only minor issues.

        0.8 - 1.0 : Excellent writing. Clear, engaging, polished,
                    well-structured, and easy to read.

        IMPORTANT:
        - Be reasonably generous when the story is understandable.
        - Give scores based on the actual quality of the story.
        - Do not automatically give a very low score.
        - Return the score between 0.0 and 1.0.
        - Provide one short line of feedback.

        Story:
        {{story}}
        """
        )
        @Agent("Scores the story and suggests improvements")
        Review score(@V("story") String story);
    }

    public interface StyleImprover {
        @UserMessage("Improve the story using this feedback: {{review}}. Return only the story. Story: {{story}}")
        @Agent("Improves the story based on feedback")
        String improve(@V("story") String story, @V("review") Review review);
    }
    public interface StreamingStoryWriter {
        @UserMessage("Write a short story about {{topic}}.")
        @Agent("Writes a story, streaming as it goes")
        String write(@V("topic") String topic);
    }

    // --- Reviewers (parallel) ---

    public interface SeoReviewer {
        @UserMessage("Rate the SEO friendliness of this text from 0.0 to 1.0 with one tip. Text: {{story}}")
        @Agent("Reviews the text for search friendliness")
        Review review(@V("story") String story);
    }

    public interface ReadabilityReviewer {
        @UserMessage("Rate how easy this text is to read from 0.0 to 1.0 with one tip. Text: {{story}}")
        @Agent("Reviews the text for readability")
        Review review(@V("story") String story);
    }

    public interface TopicSummarizer {
        @UserMessage("Summarise this topic in exactly one sentence: {{topic}}")
        @Agent("Summarises a single topic")
        String summarise(@V("topic") String topic);
    }


    public interface Classifier {
        @SystemMessage("You classify customer messages")
        @UserMessage("Classify this message as QUESTION, COMPLAINT or PRAISE: {{message}}")
        @Agent("Classifies the customer's message")
        Intent classify(@V("message") String message);
    }

    public interface QuestionResponder {
        @UserMessage("Answer this customer question helpfully in two sentences: {{message}}")
        @Agent("Answers questions")
        String reply(@V("message") String message);
    }

    public interface ComplaintResponder {
        @UserMessage("Respond to this complaint with empathy and a next step, in two sentences: {{message}}")
        @Agent("Handles complaints")
        String reply(@V("message") String message);
    }

    public interface PraiseResponder {
        @UserMessage("Thank the customer warmly for this positive message in one sentence: {{message}}")
        @Agent("Handles praise")
        String reply(@V("message") String message);
    }
}
