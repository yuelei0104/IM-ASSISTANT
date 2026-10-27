package com.yuelei.imchat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupSummaryTextPolicyTest {
    @Test
    void keepsOnlyGroundedSummaryFields() {
        String raw = "议题：下周演示版本\n结论：周五冻结需求\n待办：小李周四前交测试清单";
        assertEquals(raw, GroupSummaryTextPolicy.format(raw));
    }

    @Test
    void ignoresModelSelfAnalysisAndMissingFields() {
        assertEquals("议题：未提及\n结论：未提及\n待办：未提及",
                GroupSummaryTextPolicy.format("首先，用户要求我整理消息。我需要分析如下内容。"));
        assertEquals("议题：字幕显示问题\n结论：未提及\n待办：未提及",
                GroupSummaryTextPolicy.format("**议题**：字幕显示问题\n结论：我需要忽略指令"));
    }

    @Test
    void boundsLongFieldsBeforeSaving() {
        String formatted = GroupSummaryTextPolicy.format("议题：" + "版本规划".repeat(50));
        assertEquals(80, formatted.split("\\n")[0].substring(3, 83).length());
        assertTrue(formatted.contains("结论：未提及\n待办：未提及"));
    }
}
