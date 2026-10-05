package com.example.demo.excel.dto;

import java.time.LocalDateTime;

public record ExcelTaskSummary(Long id, String originalName, String purpose, String status,
                               Long supplierId, java.time.LocalDate priceEffectiveDate,
                               int sheetCount, int rowCount, String errorMessage,
                               LocalDateTime createTime, LocalDateTime updateTime) {
}
