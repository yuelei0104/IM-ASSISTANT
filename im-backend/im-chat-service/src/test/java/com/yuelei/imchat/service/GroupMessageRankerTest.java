package com.yuelei.imchat.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroupMessageRankerTest {
    private static final String QUESTION = "接口清单什么时候给";

    @Test
    void literalOverlapOutranksAMarginallyCloserButIrrelevantMessage() {
        var ranked = GroupMessageRanker.rank(
                List.of(new GroupVectorStore.Hit(1, 0.30), new GroupVectorStore.Hit(2, 0.28)),
                Map.of(1L, "周五前把接口清单发出来", 2L, "会议室改到三楼"),
                QUESTION, 5);
        assertEquals(List.of(1L, 2L),
                ranked.stream().map(GroupMessageRanker.Ranked::messageId).toList());
    }

    @Test
    void clearlyWorseHitsAreCutSoThePromptStaysFocused() {
        var ranked = GroupMessageRanker.rank(
                List.of(new GroupVectorStore.Hit(1, 0.20), new GroupVectorStore.Hit(3, 0.95)),
                Map.of(1L, "周五前把接口清单发出来", 3L, "会议室改到三楼"),
                QUESTION, 5);
        assertEquals(List.of(1L),
                ranked.stream().map(GroupMessageRanker.Ranked::messageId).toList());
    }

    @Test
    void limitAndMissingTextAreRespected() {
        var hits = List.of(new GroupVectorStore.Hit(1, 0.20),
                new GroupVectorStore.Hit(2, 0.22), new GroupVectorStore.Hit(9, 0.18));
        var ranked = GroupMessageRanker.rank(hits,
                Map.of(1L, "周五前把接口清单发出来", 2L, "接口清单在共享盘"),
                QUESTION, 1);
        assertEquals(1, ranked.size());
        // A hit whose text is gone must not be ranked or cited.
        assertEquals(List.of(1L),
                GroupMessageRanker.rank(hits, Map.of(1L, "周五前把接口清单发出来"),
                        QUESTION, 5).stream().map(GroupMessageRanker.Ranked::messageId).toList());
    }

    @Test
    void emptyInputProducesNoPrompt() {
        assertEquals(List.of(), GroupMessageRanker.rank(List.of(), Map.of(), QUESTION, 5));
        assertEquals(List.of(), GroupMessageRanker.rank(
                List.of(new GroupVectorStore.Hit(1, 0.2)), Map.of(1L, "x"), QUESTION, 0));
    }
}
