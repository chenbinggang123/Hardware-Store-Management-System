package com.example.demo.excel.dto;

import java.util.List;

public record ExcelComparisonItemPage(int page, int size, long totalElements, int totalPages,
                                      List<ExcelComparisonItemView> items) {
}
