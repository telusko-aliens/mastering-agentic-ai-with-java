package com.telusko.rag_eval_langsmith.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

@Service
public class RagService 
{
	
    public static final String NO_ANSWER = "I don't know based on the available documents.";
    private static final double SIMILARITY_THRESHOLD = 0.35;
    private final ChatClient chat;
    private final VectorStore vectorStore;
    private final Tracer tracer;
    private final String modelName;
    
    public RagService(ChatClient.Builder builder,
            VectorStore vectorStore,
            Tracer tracer,
            org.springframework.core.env.Environment env) {

    	this.vectorStore = vectorStore;
    	this.tracer = tracer;
    	this.modelName = env.getProperty("spring.ai.openai.chat.model", "unknown");

    	this.chat = builder
      // The refusal sentence is given verbatim and twice on purpose. Told
      // only to "say you do not know", the model shortens it to "I don't
      // know." and the evaluator's exact-sentence check stops matching.
    			.defaultSystem("""
              You answer questions about the NovaCart refund policy.

              Use only the context you are given. Do not use outside knowledge
              and do not guess. Quote numbers and timeframes exactly as the
              policy states them.

              If the context does not answer the question, reply with exactly
              this sentence and nothing else:
              I don't know based on the available documents.

              Otherwise answer in at most three sentences, in plain text.
              Answer only what was asked. Do not add other rules or
              timeframes the question did not ask about.""")
      	.build();
    }
    
    public record Answer(String question,
            String answer,
            int topK,
            List<String> sources,
            List<String> chunks,
            long millis,
            String model,
            boolean refused) {}
    
    public Answer ask(String question, int topK) {

        long started = System.currentTimeMillis();

        // ------------------------------------------------------------------
        // GUARD: empty question.
        // OpenAI's embedding model rejects an empty string with an error,
        // and the class would see a 500 stack trace. Nothing to search for,
        // so refuse right here.
        // ------------------------------------------------------------------
        if (question == null || question.isBlank()) {
            return new Answer("", NO_ANSWER, topK, List.of(), List.of(),
                    System.currentTimeMillis() - started, modelName, true);
        }

        // ==================================================================
        // STEP 1: RETRIEVE  (inside our own span "rag.retrieval")
        // ==================================================================
        //
        // How a span works - 3 parts:
        //   start()          -> the timer starts
        //   tag(key, value)  -> attach info we want to see in LangSmith
        //   end()            -> the timer stops (in finally, so it ALWAYS ends)
        // tracer.withSpan(...) makes this span the "current" one, so anything
        // that happens inside becomes its child in the trace tree.
        Span retrieval = tracer.nextSpan().name("rag.retrieval").start();
        List<Document> retrieved;
        try (var ignored = tracer.withSpan(retrieval)) {
            retrieval.tag("rag.question", question);
            retrieval.tag("rag.top_k", String.valueOf(topK));

            // The actual search:
            //   1. the question is turned into a vector (embedding)
            //   2. pgvector finds the topK stored vectors closest to it
            //   3. anything with similarity below 0.35 is dropped
            retrieved = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(topK)
                            .similarityThreshold(SIMILARITY_THRESHOLD)
                            .build());

            // Record how many chunks we found. 0 here = a refusal is coming.
            retrieval.tag("rag.documents_retrieved",
                    String.valueOf(retrieved == null ? 0 : retrieved.size()));
        } finally {
            retrieval.end();
        }

        // Never work with null; use an empty list instead.
        List<Document> docs = retrieved == null ? List.of() : retrieved;

        // ------------------------------------------------------------------
        // NO EVIDENCE -> REFUSE WITHOUT CALLING THE MODEL.
        // Faster (~250 ms), free, and the model can't be tempted to invent
        // an answer from its general knowledge.
        // ------------------------------------------------------------------
        if (docs.isEmpty()) {
            return new Answer(question, NO_ANSWER, topK, List.of(), List.of(),
                    System.currentTimeMillis() - started, modelName, true);
        }

        // ==================================================================
        // STEP 2 + 3: AUGMENT and GENERATE  (inside span "rag.generation")
        // ==================================================================
        Span generation = tracer.nextSpan().name("rag.generation").start();
        String answer;
        try (var ignored = tracer.withSpan(generation)) {
            generation.tag("rag.model", modelName);
            generation.tag("rag.context_chunks", String.valueOf(docs.size()));

            // The ChatClient "fluent" style, read it like a sentence:
            //   prompt()          -> start a new request
            //   .user(question)   -> the user's message
            //   .advisors(...)    -> the RAG advisor: before sending, it searches
            //                        the vector store and adds the chunks into
            //                        the prompt (= AUGMENT)
            //   .call()           -> send to OpenAI and wait (= GENERATE)
            //   .content()        -> give me just the answer text
            //
            // Note: the advisor searches AGAIN (same settings, same results).
            // We did our own search above so we can show the user the exact
            // chunks that were used, instead of guessing.
            answer = chat.prompt()
                    .user(question)
                    .advisors(advisorFor(topK))
                    .call()
                    .content();

            generation.tag("rag.answer_length",
                    String.valueOf(answer == null ? 0 : answer.length()));
        } finally {
            generation.end();
        }

        // Clean up the answer, and detect whether it was a refusal.
        String text = answer == null ? NO_ANSWER : answer.strip();
        boolean refused = text.toLowerCase().contains("i don't know")
                || text.toLowerCase().contains("i do not know");

        // Build the final response. Chunks are returned as plain text so the
        // UI can show the "evidence" under the answer.
        return new Answer(
                question,
                text,
                topK,
                sourcesOf(docs),
                docs.stream().map(Document::getText).toList(),
                System.currentTimeMillis() - started,
                modelName,
                refused);
    }

    /**
     * Builds the RAG advisor. A new one per call because topK changes per call.
     *
     * The advisor has two parts:
     *   1. documentRetriever -> HOW to find chunks (same store, topK, threshold as above)
     *   2. queryAugmenter    -> HOW to put chunks into the prompt
     *
     * allowEmptyContext(false) + emptyContextPromptTemplate:
     *   "if no chunks were found, DON'T let the model answer freely; instead
     *    tell it to reply with the refusal sentence."
     */
    private RetrievalAugmentationAdvisor advisorFor(int topK) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .topK(topK)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .build())
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(false)
                        .emptyContextPromptTemplate(new PromptTemplate(
                                "Reply with exactly this sentence and nothing else: "
                                        + NO_ANSWER))
                        .build())
                .build();
    }

    /**
     * Gets the list of source file names from the chunks, without duplicates,
     * keeping the order they came back in.
     * LinkedHashSet = a Set (no duplicates) that remembers insertion order.
     * With 3 chunks all from refund-policy.txt, the result is just ["refund-policy.txt"].
     */
    private static List<String> sourcesOf(List<Document> docs) {
        Set<String> names = new LinkedHashSet<>();
        for (Document doc : docs) {
            // "source" was put in the metadata during ingestion.
            Object source = doc.getMetadata().get("source");
            names.add(source == null ? "unknown" : String.valueOf(source));
        }
        return List.copyOf(names);
    }

}
