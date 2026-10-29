package com.yuelei.imchat.service;

import com.yuelei.imchat.model.GroupMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Keep model commentary and unstructured reasoning out of persisted summaries. */
final class GroupSummaryTextPolicy {
    private static final String UNKNOWN = "未提及";
    private static final Pattern LINE = Pattern.compile(
            "(?m)^\\s*\\*{0,2}(议题|结论|待办)\\*{0,2}[：:]\\s*([^\\r\\n]*)");
    private static final Pattern META = Pattern.compile(
            "用户要求|我需要|我的任务|提示词|系统指令|非可信文本|忽略.*指令|分析如下|思考过程");
    private static final Pattern INSTRUCTION = Pattern.compile(
            "忽略|泄露|系统消息|角色扮演|回答.*问题|执行.*指令|输出.*(密码|令牌)");
    private static final Pattern TOPIC = Pattern.compile("^【([^】]{2,30})】(.+)$");
    private static final Pattern CONCLUSION = Pattern.compile(
            "^(?:今天的)?(?:第[一二三四五六七八九十]+条)?结论[：:]\\s*(.+)$");
    private static final Pattern ASSIGNMENT = Pattern.compile("^[\\p{IsHan}]{2,6}负责.{3,}$");

    private GroupSummaryTextPolicy() { }

    static String format(String raw) {
        if (raw == null || raw.isBlank()) return empty();
        Map<String, String> fields = new HashMap<>();
        var matcher = LINE.matcher(raw);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2).replace("**", "").strip();
            if (fields.containsKey(key)) continue;
            if (value.isBlank() || value.equals("无") || value.equals("暂无")
                    || value.startsWith("未提及") || META.matcher(value).find()
                    || INSTRUCTION.matcher(value).find()) {
                fields.put(key, UNKNOWN);
            } else {
                fields.put(key, value.length() > 80 ? value.substring(0, 80) + "…" : value);
            }
        }
        if (fields.isEmpty()) return empty();
        return "议题：" + fields.getOrDefault("议题", UNKNOWN)
                + "\n结论：" + fields.getOrDefault("结论", UNKNOWN)
                + "\n待办：" + fields.getOrDefault("待办", UNKNOWN);
    }

    /** Recover only facts explicitly labelled in authorized source messages. */
    static String format(String raw, List<GroupMessage> messages) {
        String proposed = format(raw);
        Map<String, String> fields = new HashMap<>();
        var parsed = LINE.matcher(proposed);
        while (parsed.find()) fields.put(parsed.group(1), parsed.group(2));
        String topic = null;
        String conclusion = null;
        String task = null;
        for (GroupMessage message : messages) {
            String source = message.body().strip();
            if (META.matcher(source).find() || INSTRUCTION.matcher(source).find()) continue;
            var match = LINE.matcher(source);
            if (match.find() && match.start() == 0) {
                String key = match.group(1);
                if (UNKNOWN.equals(fields.get(key)) && valid(match.group(2)))
                    fields.put(key, shorten(match.group(2)));
                continue;
            }
            // Source messages can state facts without using the model's three labels.
            var heading = TOPIC.matcher(source);
            if (heading.matches()) {
                if (valid(heading.group(2)) && heading.group(2).strip().length() > 8)
                    topic = heading.group(1).strip();
                source = heading.group(2).strip();
            }
            var decision = CONCLUSION.matcher(source);
            if (conclusion == null && decision.matches() && valid(decision.group(1)))
                conclusion = shorten(decision.group(1));
            if (task == null && ASSIGNMENT.matcher(source).matches() && valid(source))
                task = shorten(source);
        }
        if (UNKNOWN.equals(fields.get("议题")) && topic != null) fields.put("议题", topic);
        if (UNKNOWN.equals(fields.get("结论")) && conclusion != null) fields.put("结论", conclusion);
        if (UNKNOWN.equals(fields.get("待办")) && task != null) fields.put("待办", task);
        return "议题：" + fields.getOrDefault("议题", UNKNOWN)
                + "\n结论：" + fields.getOrDefault("结论", UNKNOWN)
                + "\n待办：" + fields.getOrDefault("待办", UNKNOWN);
    }

    private static boolean valid(String value) {
        String text = value.strip();
        return !text.isEmpty() && !text.startsWith(UNKNOWN)
                && !META.matcher(text).find() && !INSTRUCTION.matcher(text).find();
    }

    private static String shorten(String value) {
        String text = value.strip();
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    private static String empty() {
        return "议题：未提及\n结论：未提及\n待办：未提及";
    }
}
