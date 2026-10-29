package com.yuelei.imchat.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Turns a wide vector recall pool into the handful of messages worth prompting with.
 *
 * <p>Vector distance alone ranks paraphrases well but loses to near-duplicate chatter;
 * literal overlap alone cannot match paraphrases at all. Combining them, then cutting
 * everything that scores clearly below the best hit, keeps the prompt focused. That cut
 * matters: a group chat where only one message answers the question should not spend the
 * model's attention on seven unrelated ones.
 */
final class GroupMessageRanker {
    /** How far below the best hybrid score a message may fall and still enter the prompt. */
    static final double SCORE_MARGIN = 0.12;
    private static final double VECTOR_WEIGHT = 0.75;
    private static final double LEXICAL_WEIGHT = 0.25;

    record Ranked(long messageId, double distance, double score) { }

    private GroupMessageRanker() { }

    static List<Ranked> rank(List<GroupVectorStore.Hit> hits, Map<Long, String> texts,
                             String question, int limit) {
        if (hits.isEmpty() || limit < 1) return List.of();
        var questionPairs = pairs(question);
        var scored = new ArrayList<Ranked>();
        for (var hit : hits) {
            String text = texts.get(hit.messageId());
            if (text == null) continue;
            double similarity = 1 - Math.min(Math.max(hit.distance(), 0), 2) / 2;
            double lexical = questionPairs.isEmpty() ? 0 : overlap(text, questionPairs);
            scored.add(new Ranked(hit.messageId(), hit.distance(),
                    VECTOR_WEIGHT * similarity + LEXICAL_WEIGHT * lexical));
        }
        if (scored.isEmpty()) return List.of();
        scored.sort(Comparator.comparingDouble(Ranked::score).reversed()
                .thenComparingDouble(Ranked::distance)
                .thenComparing(Comparator.comparingLong(Ranked::messageId).reversed()));
        double best = scored.get(0).score();
        var kept = new ArrayList<Ranked>();
        for (var candidate : scored) {
            if (kept.size() >= limit) break;
            if (!kept.isEmpty() && candidate.score() < best - SCORE_MARGIN) break;
            kept.add(candidate);
        }
        return List.copyOf(kept);
    }

    private static Set<String> pairs(String text) {
        var result = new LinkedHashSet<String>();
        if (text == null) return result;
        String value = text.strip();
        for (int i = 0; i + 1 < value.length(); i++) {
            if (Character.UnicodeScript.of(value.charAt(i)) == Character.UnicodeScript.HAN
                    || Character.UnicodeScript.of(value.charAt(i + 1)) == Character.UnicodeScript.HAN) {
                result.add(value.substring(i, i + 2));
            }
        }
        return result;
    }

    /** Fraction of the question's character pairs that also appear in the message. */
    private static double overlap(String text, Set<String> questionPairs) {
        String value = text.strip();
        int shared = 0;
        for (String pair : questionPairs) {
            if (value.contains(pair)) shared++;
        }
        return (double) shared / questionPairs.size();
    }
}
