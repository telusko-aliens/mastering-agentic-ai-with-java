package com.telusko.rag_eval_langsmith.service;


import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
//Read, chunks, embeddings and store doc

@Service
public class IngestionService
{
    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    
    public static final String SOURCE = "refund-policy.txt";

    private final VectorStore vectorStore;
    private final Resource policy;
    
    public IngestionService(VectorStore vectorStore,
            @Value("classpath:docs/refund-policy.txt") Resource policy) {
    	this.vectorStore = vectorStore;
    	this.policy = policy;
    }
    
    
   // * Reads, splits, embeds and stores the policy.

    
    public Map<String, Object> ingest(boolean force) {

        long started = System.currentTimeMillis();

        // Calling this endpoint twice should not double every chunk, and in a class
        // somebody always clicks twice. A cheap search is enough to tell: if anything
        // comes back, the document is already in.
        int existing = count();
        if (existing > 0 && !force) {
            log.info("ingest skipped, {} chunks already stored", existing);
            return Map.of(
                    "status", "skipped",
                    "reason", "Already ingested. Pass force=true to ingest again.",
                    "chunks", existing,
                    "source", SOURCE);
        }

        // force does not mean "add again", it means "replace". Adding without deleting
        // leaves the old chunks in the store, and they keep being retrieved: edit the
        // document, re-ingest, and the answers still quote the old version. That
        // happened while writing this, and it is the most confusing kind of RAG bug
        // because nothing fails.
        int removed = 0;
        if (existing > 0) {
            removed = deleteExisting();
            log.info("force ingest: removed {} existing chunks first", removed);
        }

        // 1. Read. TextReader produces ONE Document holding the whole file.
        //    The metadata map is copied onto every chunk the splitter makes, which is
        //    how the answer later knows which file a sentence came from.
        TextReader reader = new TextReader(policy);
        reader.getCustomMetadata().put("source", SOURCE);
        List<Document> whole = reader.read();

        // 2. Split. A whole policy is too big to embed usefully: one vector for ten
        //    pages points at the average of ten pages and matches nothing well.
        //    These numbers are the interesting knob in the whole pipeline, so they are
        //    spelled out rather than left to the defaults.
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(150)             // tokens per chunk
                .withMinChunkSizeChars(100)     // do not emit a chunk shorter than this
                .withMinChunkLengthToEmbed(10)  // discard scraps
                .withMaxNumChunks(5000)
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = splitter.apply(whole);

        // 3 and 4. Embed and store. One call does both: the VectorStore asks the
        //          EmbeddingModel for a vector per chunk and writes the rows.
        vectorStore.add(chunks);

        long took = System.currentTimeMillis() - started;
        log.info("ingested {} chunks from {} in {} ms", chunks.size(), SOURCE, took);

        return Map.of(
                "status", "ingested",
                "chunks", chunks.size(),
                "removedFirst", removed,
                "source", SOURCE,
                "millis", took,
                "chunkSizeTokens", 150);
    }
    
    //How many chunks are in the store. 
    
    public int count() {
        try {
            // There is no count() on VectorStore, so ask for far more than the document
            // can produce and see how many come back. Fine for one small document.
            List<Document> all = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("refund")
                            .topK(500)
                            .similarityThreshold(0.0)
                            .build());
            return all == null ? 0 : all.size();
        } catch (Exception e) {
            log.warn("could not count chunks: {}", e.getMessage());
            return 0;
        }
    }
    
    private int deleteExisting() {
        List<Document> old = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("refund")
                        .topK(1000)
                        .similarityThreshold(0.0)
                        .build());

        if (old == null || old.isEmpty()) {
            return 0;
        }

        vectorStore.delete(old.stream().map(Document::getId).toList());
        return old.size();
    }
    
    public String policyText() {
        try (var in = policy.getInputStream()) {
            return new String(in.readAllBytes());
        } catch (Exception e) {
            return "Could not read " + SOURCE + ": " + e.getMessage();
        }
    }


}
