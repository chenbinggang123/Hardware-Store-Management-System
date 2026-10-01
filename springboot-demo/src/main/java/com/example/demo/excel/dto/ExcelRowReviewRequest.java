package com.example.demo.excel.dto;

public record ExcelRowReviewRequest(Long expectedVersion, String decision, Long productId, String reason) {
}
