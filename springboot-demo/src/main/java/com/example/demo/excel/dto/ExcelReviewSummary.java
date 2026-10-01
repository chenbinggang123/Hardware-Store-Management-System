package com.example.demo.excel.dto;

import java.util.List;

public record ExcelReviewSummary(Long taskId, Long sheetId, String status, long version, int totalRows,
                                 long matchedRows, long readyToCreateRows, long needsReviewRows, long invalidRows,
                                 long excludedRows,
                                 List<ExcelColumnMappingView> mappings) {
}
