package com.telusko.agenticaisystem.service;

import com.telusko.agenticaisystem.agents.Agents.*;
import com.telusko.agenticaisystem.agents.Intent;
import com.telusko.agenticaisystem.agents.Review;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Service
public class AgenticService
{
    private ChatModel chatModel;
    private StreamingChatModel streamingChatModel;

    public AgenticService(ChatModel chatModel, StreamingChatModel streamingChatModel)
    {
        this.chatModel=chatModel;
        this.streamingChatModel=streamingChatModel;
    }

    public String basicAgent(String topic)
    {
        StoryWriter writer = AgenticServices.agentBuilder(StoryWriter.class)
                .chatModel(chatModel)
                .outputKey("story")
                .build();

        return writer.write(topic);
    }

    public String sequential(String topic, String audience)
    {
        //first agent --> create story
       StoryWriter writer = AgenticServices.agentBuilder(StoryWriter.class)
                .chatModel(chatModel)
                .outputKey("story")
                .build();

        //second agent --> modfies story --> target audience
       AudienceEditor audienceEditor = AgenticServices.agentBuilder(AudienceEditor.class)
                .chatModel(chatModel)
                .outputKey("story")
                .build();

        //third agent
        StyleEditor styleEditor = AgenticServices.agentBuilder(StyleEditor.class)
                .chatModel(chatModel)
                .outputKey("story")
                .build();

//        System.out.println("----------------------------");
//        HashMap<String, Object> input = new HashMap<>();
//        input.put("topic", topic);
//        input.put("audience", audience);
//
//        System.out.println("Agent 1 --> Story writer --> 1");
//        writer.write(input);




        //sequential workflow
        UntypedAgent pipeline = AgenticServices.sequenceBuilder()
                .subAgents(
                        writer,
                        audienceEditor,
                        styleEditor
                )
                .outputKey("story")
                .build();

            return (String) pipeline.invoke(Map.of(
                    "topic", topic,
                    "audience", audience
            ));
    }
    public String loop(String story)
    {
        // Agent responsible for evaluating the story.
        StyleScorer scorer =
                AgenticServices.agentBuilder(StyleScorer.class)
                        .chatModel(chatModel)

                        // The scorer returns a Review object.
                        // Store it under "review".
                        .outputKey("review")
                        .build();
        Review res = scorer.score(story);
        System.out.println("Score :"+res.score());
        System.out.println("Feedback : "+ res.feedback());


        // Agent responsible for improving the story.
        StyleImprover improver =
                AgenticServices.agentBuilder(StyleImprover.class)
                        .chatModel(chatModel)

                        // The improved story becomes the
                        // current value of "story".
                        .outputKey("story")
                        .build();

        UntypedAgent refiner = AgenticServices.loopBuilder()
                .subAgents(scorer,
                        improver)
                .outputKey("story")
                .maxIterations(2)
                .exitCondition(scope -> ((Review) scope.readState("review"))
                        .score() >= 0.8)
                .build();

        return (String) refiner.invoke(
                Map.of("story", story)
        );
    }
    public Review parallelAgents(String text)
    {
        // Create SEO review agent.
        SeoReviewer seo =
                AgenticServices.agentBuilder(SeoReviewer.class)
                        .chatModel(chatModel)
                        .outputKey("seoReview")
                        .build();


        // Create readability review agent.
        ReadabilityReviewer readability =
                AgenticServices.agentBuilder(ReadabilityReviewer.class)
                        .chatModel(chatModel)
                        .outputKey("readabilityReview")
                        .build();

        var executor=Executors.newFixedThreadPool(2);

        UntypedAgent pipeline = AgenticServices.parallelBuilder()
                .subAgents(
                        seo,
                        readability
                )
                .executor(executor)
                .outputKey("finalReview")
                .output(
                        agenticScope -> {
                            Review a = (Review) agenticScope.readState("seoReview");

                            Review b = (Review) agenticScope.readState("readabilityReview");

                            return new Review(
                                    a.score() + b.score() / 2.0,
                                    "SEO: " + a.feedback() + " | Readability: " + b.feedback()
                            );
                        })
                .build();

//       return (Review) pipeline.invoke(
//                Map.of("story", text));
        Review result=(Review) pipeline.invoke(Map.of("story", text));
        executor.shutdown();
        return result;
    }
    public Object mapper(List<String> topics)
    {
        TopicSummarizer summarizer =
                AgenticServices.agentBuilder(TopicSummarizer.class)
                        .chatModel(chatModel)
                        .outputKey("summary")
                        .build();

        var executor=Executors.newFixedThreadPool(4);
        UntypedAgent batch = AgenticServices.parallelMapperBuilder()
                .subAgents(summarizer)
                .itemsProvider("topics")
                .outputKey("summaries")
                .executor(executor)
                .build();
        Object result = batch.invoke(
                Map.of("topics", topics)
        );
        executor.shutdown();
        return result;
    }
    public String condionalAgents(String message)
    {
        // First create the classifier agent.
        Classifier classifier =
                AgenticServices.agentBuilder(Classifier.class)
                        .chatModel(chatModel)

                        // Classifier returns an Intent.
                        .outputKey("intent")
                        .build();
      String res=classifier.classify(message).toString();
        System.out.println(res);


        // Agent used when intent is QUESTION.
        QuestionResponder question =
                AgenticServices.agentBuilder(QuestionResponder.class)
                        .chatModel(chatModel)
                        .outputKey("answer")
                        .build();

        String reply=question.reply(message);
        System.out.println(reply);


        // Agent used when intent is COMPLAINT.
        ComplaintResponder complaint =
                AgenticServices.agentBuilder(ComplaintResponder.class)
                        .chatModel(chatModel)
                        .outputKey("answer")
                        .build();


        // Agent used when intent is PRAISE.
        PraiseResponder praise =
                AgenticServices.agentBuilder(PraiseResponder.class)
                        .chatModel(chatModel)
                        .outputKey("answer")
                        .build();
        UntypedAgent pipeline =
                AgenticServices.conditionalBuilder()
                .subAgents(
                        agenticScope -> agenticScope.readState("intent") == Intent.QUESTION, question
                )
                .subAgents(
                        agenticScope -> agenticScope.readState("intent") == Intent.COMPLAINT, complaint

                ).subAgents(
                        agenticScope -> agenticScope.readState("intent") == Intent.PRAISE, praise

                )
                .build();
        UntypedAgent finalPipeline = AgenticServices.sequenceBuilder()
                .subAgents(classifier, pipeline)
                .outputKey("answer")

                .build();
        return (String) finalPipeline.invoke(Map.of(
                "message", message
                )
        );

    }


}
