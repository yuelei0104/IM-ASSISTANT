package com.yuelei.imchat.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroupAgentAnswerParserTest {
    @Test
    void keepsOnlyCitedIdsFromTheAuthorizedContext() {
        var parsed = GroupAgentAnswerParser.parse(
                "答案：下周一提交设计稿。\n依据：消息 42，消息 999，消息 42", Set.of(41L, 42L));
        assertEquals("下周一提交设计稿。", parsed.answer());
        assertEquals(List.of(42L), parsed.sourceIds());
    }

    @Test
    void missingEvidenceOrMalformedAnswerDoesNotClaimSources() {
        var missing = GroupAgentAnswerParser.parse(
                "答案：" + GroupAgentEngine.NO_EVIDENCE + "\n依据：无", Set.of(19L));
        assertEquals(GroupAgentEngine.NO_EVIDENCE, missing.answer());
        assertEquals(List.of(), missing.sourceIds());
        assertEquals(List.of(), GroupAgentAnswerParser.parse("很长的猜测", Set.of(19L)).sourceIds());
    }

    @Test
    void overlyLongModelAnswerIsBounded() {
        var parsed = GroupAgentAnswerParser.parse("答案：" + "详细分析".repeat(80)
                + "\n依据：消息 7", Set.of(7L));
        assertEquals(List.of(7L), parsed.sourceIds());
        org.junit.jupiter.api.Assertions.assertTrue(parsed.answer().length() <= 121);
    }

    @Test
    void explicitSingleMessageCanBeQuotedWithoutTrustingModelCitationFormat() {
        var caption = new GroupAgentEngine.Candidate(17L, "仅原成员可见的字幕");
        var result = GroupAgentAnswerParser.literalFallback("原成员的字幕是什么", List.of(caption));
        assertEquals(List.of(17L), result.sourceIds());
        assertEquals("群消息写道：“仅原成员可见的字幕”。", result.answer());
        assertEquals(List.of(), GroupAgentAnswerParser.literalFallback(
                "下次会议要做什么", List.of(caption)).sourceIds());
    }
}
