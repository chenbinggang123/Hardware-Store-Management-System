package com.example.demo.excel.dto;

import com.example.demo.excel.entity.ExcelTaskOutput;

import java.time.LocalDateTime;

public record ExcelOutputView(Long id, Long taskId, String outputType, String name, String mimeType,
                              long size, String status, String errorMessage,
                              LocalDateTime createTime, LocalDateTime expiresAt) {
    public static ExcelOutputView from(ExcelTaskOutput value) {
        return new ExcelOutputView(value.getId(), value.getTaskId(), value.getOutputType(),
                value.getOriginalName(), value.getMimeType(), value.getFileSize() == null ? 0 : value.getFileSize(),
                value.getStatus(), value.getErrorMessage(), value.getCreateTime(), value.getExpiresAt());
    }
}
