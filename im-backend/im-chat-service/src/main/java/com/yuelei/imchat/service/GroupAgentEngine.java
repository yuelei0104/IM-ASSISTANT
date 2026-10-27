package com.yuelei.imchat.service;

import java.util.List;

/** A request-scoped, non-persistent RAG corpus; candidates are already authorized. */
public interface GroupAgentEngine {
    String NO_EVIDENCE = "当前可见的群消息中没有足够依据回答这个问题。";
    record Candidate(long id, String text) { }
    record Draft(String answer, List<Long> sourceIds, List<Long> contextIds) {
        public Draft(String answer, List<Long> sourceIds) {
            this(answer, sourceIds, sourceIds);
        }
    }

    Draft answer(String question, List<Candidate> visibleMessages);
}
