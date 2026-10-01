package com.example.demo.excel.dto;

import java.time.LocalDateTime;

public record ExcelTaskSummary(Long id, String originalName, String purpose, String status,
                               int sheetCount, int rowCount, String errorMessage,
                               LocalDateTime createTime, LocalDateTime updateTime) {
}
