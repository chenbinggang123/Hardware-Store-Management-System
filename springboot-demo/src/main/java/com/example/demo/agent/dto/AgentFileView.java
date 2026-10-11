package com.example.demo.agent.dto;

import com.example.demo.excel.entity.ExcelTaskOutput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

/** A file the agent produced for the current user, as shown in the conversation and in “我的文件”. */
public record AgentFileView(Long id, String name, String mimeType, long size, String status, String sourceType,
                            Long runId, Long conversationId, Long attachmentId, LocalDateTime createTime,
                            LocalDateTime expiresAt, String downloadPath, String errorMessage,
                            Map<String, Object> summary) {
    public static AgentFileView from(ExcelTaskOutput output, ObjectMapper objectMapper) {
        Map<String, Object> summary = Map.of();
        if (objectMapper != null && StringUtils.hasText(output.getSummaryJson())) {
            try {
                summary = objectMapper.readValue(output.getSummaryJson(), new TypeReference<Map<String, Object>>() { });
            } catch (Exception ignored) {
                summary = Map.of();
            }
        }
        return new AgentFileView(output.getId(), output.getOriginalName(), output.getMimeType(),
                output.getFileSize() == null ? 0 : output.getFileSize(), output.getStatus(),
                output.getSourceType() == null ? "EXCEL_TASK" : output.getSourceType(),
                output.getRunId(), output.getConversationId(), output.getSourceId(),
                output.getCreateTime(), output.getExpiresAt(),
                "/agent/outputs/" + output.getId() + "/download", output.getErrorMessage(), summary);
    }
}
