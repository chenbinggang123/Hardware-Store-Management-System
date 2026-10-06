package com.example.demo.excel.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ExcelTaskDetail(Long id, String originalName, String purpose, String status,
                              Long supplierId, java.time.LocalDate priceEffectiveDate,
                              String mimeType, String sourceFormat, String parseMethod,
                              String ocrProvider, String ocrModelVersion, String ocrRequestId,
                              long fileSize, String sha256, int sheetCount,
                              int rowCount, String errorMessage, LocalDateTime createTime,
                              LocalDateTime updateTime, long version, List<ExcelTaskSheetView> sheets) {
}
