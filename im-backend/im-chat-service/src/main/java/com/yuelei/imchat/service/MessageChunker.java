package com.yuelei.imchat.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a long message into overlapping windows for embedding.
 *
 * <p>A single vector for a 1200-character message blurs every topic it mentions,
 * so a question about one paragraph matches weakly. Windows of {@link #WINDOW}
 * characters keep each vector focused; the overlap keeps a sentence that falls on
 * a boundary readable from either side.
 */
final class MessageChunker {
    static final int WINDOW = 300;
    static final int OVERLAP = 60;
    /** Caps the work a pathological single message can cause. */
    static final int MAX_CHUNKS = 12;
    private static final int TAIL_SEARCH = 80;
    private static final String BREAKS = "。！？；\n!?;";

    private MessageChunker() { }

    static List<String> split(String text) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) return List.of();
        if (value.length() <= WINDOW) return List.of(value);
        var chunks = new ArrayList<String>();
        int start = 0;
        while (start < value.length() && chunks.size() < MAX_CHUNKS) {
            int end = Math.min(start + WINDOW, value.length());
            if (end < value.length()) {
                int floor = Math.max(start + WINDOW - TAIL_SEARCH, start + 1);
                int cut = lastBreak(value, floor, end);
                if (cut > floor) end = cut;
            }
            String chunk = value.substring(start, end).strip();
            if (!chunk.isEmpty()) chunks.add(chunk);
            if (end >= value.length()) break;
            start = Math.max(end - OVERLAP, start + 1);
        }
        return List.copyOf(chunks);
    }

    private static int lastBreak(String value, int floor, int end) {
        for (int i = end - 1; i > floor; i--) {
            if (BREAKS.indexOf(value.charAt(i)) >= 0) return i + 1;
        }
        return -1;
    }
}
