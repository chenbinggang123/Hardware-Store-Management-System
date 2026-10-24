package com.example.demo.agent.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentConversationPreviewTest {
    @Test void billingResultBecomesReadableWithoutClaimingConfirmation() {
        assertEquals("单据草稿已建立，待在编辑页核对确认。", AgentConversationPreview.format(
                "操作已成功执行。结果：{\"kind\":\"BILLING_DOCUMENT\",\"documentId\":123,\"editUrl\":\"billing.html\"}"));
    }

    @Test void removesFormattingBeforeTruncationAndKeepsBusinessSymbols() {
        assertEquals("结论 无法直接录入：规格 M6×20，数量 2，单价 ¥3.50。", AgentConversationPreview.format(
                "**结论**\n无法直接录入：规格 `M6×20`，数量 2，单价 ¥3.50。"));
        assertEquals("商品资料已更新", AgentConversationPreview.format(
                "操作已成功执行。结果：{\"message\":\"**商品资料已更新**\",\"productId\":12}"));
    }

    @Test void rawOrBrokenStructuredResultsNeverLeakParameters() {
        assertEquals("请打开对话查看处理结果。", AgentConversationPreview.format("{\"productId\":12}"));
        assertEquals("请打开对话查看处理结果。", AgentConversationPreview.format("```json\n{\"productId\":12}\n```"));
        assertEquals("请打开对话查看处理结果。", AgentConversationPreview.format("操作已成功执行。结果：{\"kind\":"));
        assertEquals("暂无消息", AgentConversationPreview.format(null));
    }
}
