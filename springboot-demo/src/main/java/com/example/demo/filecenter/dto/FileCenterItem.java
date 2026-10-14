package com.example.demo.filecenter.dto;

import java.time.LocalDateTime;

public record FileCenterItem(
        String key,
        Long id,
        String kind,
        String name,
        String mimeType,
        long size,
        String state,
        String sourceStatus,
        String purpose,
        Long conversationId,
        Long runId,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        String errorMessage,
        boolean downloadable,
        String relatedType,
        Long relatedId,
        String relatedName,
        boolean organized,
        boolean manualUpload) {
}
