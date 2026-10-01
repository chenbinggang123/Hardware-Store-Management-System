package com.example.demo.excel.dto;

import java.util.List;

public record ExcelReviewRowPage(Long taskId, Long sheetId, int page, int size,
                                 long totalElements, int totalPages, List<ExcelReviewRowView> items) {
}
