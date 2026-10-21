package com.telusko.rag_eval_langsmith.eval;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

//@JsonIgnoreProperties(ignoreUnknown = true):
//if the JSON has extra fields we didn't declare here, ignore them
//instead of throwing an error.
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationDataset(String name, String description, List<Case> cases) {

 /**
  * One test case = one question in the exam.
  *
  * id             short name shown in the results table, e.g. "return-window-standard"
  * category       type of question: simple-lookup, multi-hop, unanswerable...
  * question       what we ask the RAG app
  * expectedAnswer the CORRECT answer, used by the correctness judge
  * relevantSource the sentence in the policy that contains the answer (empty if none)
  * answerable     false = the document can't answer it, the app should refuse
  */
 @JsonIgnoreProperties(ignoreUnknown = true)
 public record Case(String id,
                    String category,
                    String question,
                    String expectedAnswer,
                    String relevantSource,
                    boolean answerable) {}
}

