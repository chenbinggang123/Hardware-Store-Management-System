package com.example.demo.excel.dto;

import java.util.List;

public record ExcelTaskRowPage(Long taskId, Long sheetId, int page, int size,
                               long totalElements, int totalPages, List<ExcelTaskRowView> items) {
}
