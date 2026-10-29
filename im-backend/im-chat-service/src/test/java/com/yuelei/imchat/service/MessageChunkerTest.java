package com.yuelei.imchat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageChunkerTest {
    @Test
    void shortMessageStaysOneChunk() {
        assertEquals(List.of("周五前给接口清单"), MessageChunker.split("  周五前给接口清单  "));
        assertEquals(List.of(), MessageChunker.split("   "));
    }

    @Test
    void longMessageIsWindowedAndStillCoversTheEnding() {
        String text = "需求评审。".repeat(200);
        List<String> chunks = MessageChunker.split(text);
        assertTrue(chunks.size() > 1, "a long message must be chunked");
        assertTrue(chunks.size() <= MessageChunker.MAX_CHUNKS, "chunk count must stay bounded");
        for (String chunk : chunks) assertTrue(chunk.length() <= MessageChunker.WINDOW);
        assertTrue(chunks.get(chunks.size() - 1).endsWith("需求评审。"),
                "the tail of the message must survive chunking");
    }

    @Test
    void consecutiveChunksOverlapInsteadOfJumpingForward() {
        // No punctuation, so each window falls on a hard 300-character cut.
        var builder = new StringBuilder();
        for (int index = 0; index < 900; index++) builder.append((char) ('一' + index % 40));
        String text = builder.toString();
        List<String> chunks = MessageChunker.split(text);
        assertTrue(chunks.size() >= 3);
        assertEquals(MessageChunker.WINDOW, chunks.get(0).length(),
                "without a break candidate the window is full");
        assertEquals(chunks.get(0).substring(MessageChunker.WINDOW - MessageChunker.OVERLAP),
                chunks.get(1).substring(0, MessageChunker.OVERLAP),
                "the next window must start inside the previous one");
        assertFalse(chunks.get(1).isBlank());
    }

    @Test
    void windowPrefersTheLastSentenceBreak() {
        String sentence = "甲".repeat(280) + "。";
        String text = sentence + "乙".repeat(200);
        List<String> chunks = MessageChunker.split(text);
        assertTrue(chunks.get(0).endsWith("。"), "the window should end at a sentence break");
    }
}
