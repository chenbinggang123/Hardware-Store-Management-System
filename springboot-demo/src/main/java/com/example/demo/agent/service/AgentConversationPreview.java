package com.example.demo.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Plain text for the history list; the original run output stays intact. */
final class AgentConversationPreview {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentConversationPreview() {}

    static String format(String value) {
        if (value == null || value.isBlank()) return "暂无消息";
        String text = value.trim();
        String payload = text.replaceFirst("^操作已成功执行[。.]\\s*结果[：:]\\s*", "");
        payload = payload.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        if (payload.startsWith("{") || payload.startsWith("[")) {
            try {
                var result = JSON.readTree(payload);
                if ("BILLING_DOCUMENT".equals(result.path("kind").asText())) {
                    return "单据草稿已建立，待在编辑页核对确认。";
                }
                if (result.path("message").isTextual() && !result.path("message").asText().isBlank()) {
                    text = result.path("message").asText();
                } else {
                    return "请打开对话查看处理结果。";
                }
            } catch (Exception ignored) {
                return "请打开对话查看处理结果。";
            }
        }
        text = text.replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "")
                .replaceAll("\\*\\*|__|`", "")
                .replaceAll("\\s+", " ").trim();
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }
}
