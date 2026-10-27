package com.yuelei.imchat.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Accept only concise answers with citations drawn from the caller's authorized snapshot. */
final class GroupAgentAnswerParser {
    private static final Pattern FORMAT = Pattern.compile("(?s)答案[：:]\\s*(.*?)\\s*依据[：:]\\s*([^\\r\\n]*)");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private GroupAgentAnswerParser() { }

    static GroupAgentEngine.Draft parse(String raw, Set<Long> permitted) {
        if (raw == null) return noEvidence();
        var format = FORMAT.matcher(raw);
        if (!format.find()) return noEvidence();
        String answer = format.group(1).replace("**", "").replaceAll("\\s+", " ").trim();
        if (answer.isEmpty() || answer.contains(GroupAgentEngine.NO_EVIDENCE)
                || format.group(2).strip().startsWith("无")) return noEvidence();

        var ids = new LinkedHashSet<Long>();
        var numbers = NUMBER.matcher(format.group(2));
        while (numbers.find() && ids.size() < 3) {
            try {
                long id = Long.parseLong(numbers.group());
                if (permitted.contains(id)) ids.add(id);
            } catch (NumberFormatException ignored) {
                // A model-produced number is not an authorized source ID.
            }
        }
        if (ids.isEmpty()) return noEvidence();
        if (answer.length() > 120) {
            int end = Math.max(answer.lastIndexOf('。', 119), answer.lastIndexOf('；', 119));
            answer = answer.substring(0, end > 20 ? end + 1 : 120).stripTrailing();
            if (!answer.endsWith("。") && !answer.endsWith("；")) answer += "…";
        }
        return new GroupAgentEngine.Draft(answer, new ArrayList<>(ids));
    }

    /** Preserve an exact, authorized quote when a small model omits citation formatting. */
    static GroupAgentEngine.Draft literalFallback(String question, List<GroupAgentEngine.Candidate> messages) {
        if (messages.size() != 1) return noEvidence();
        GroupAgentEngine.Candidate source = messages.get(0);
        String text = source.text().strip();
        if (text.length() > 120 || text.isEmpty()) return noEvidence();
        var questionPairs = new LinkedHashSet<String>();
        for (int i = 0; i + 1 < question.length(); i++) {
            if (Character.UnicodeScript.of(question.charAt(i)) == Character.UnicodeScript.HAN
                    && Character.UnicodeScript.of(question.charAt(i + 1)) == Character.UnicodeScript.HAN)
                questionPairs.add(question.substring(i, i + 2));
        }
        int shared = 0;
        var matching = new LinkedHashSet<String>();
        for (int i = 0; i + 1 < text.length(); i++) {
            String pair = text.substring(i, i + 2);
            if (questionPairs.contains(pair) && matching.add(pair)) shared++;
        }
        if (shared < 3) return noEvidence();
        return new GroupAgentEngine.Draft("群消息写道：“" + text + "”。", List.of(source.id()));
    }

    private static GroupAgentEngine.Draft noEvidence() {
        return new GroupAgentEngine.Draft(GroupAgentEngine.NO_EVIDENCE, java.util.List.of());
    }
}
