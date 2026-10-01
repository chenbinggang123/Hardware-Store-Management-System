package com.example.demo.excel.dto;

import java.util.List;

public record ExcelTaskRowView(Long id, int rowNumber, String reviewStatus, List<String> cells) {
}
