package com.example.demo.excel.dto;

public record ExcelQuoteRequest(Long expectedVersion, String pricingMode, Long customerId,
                                boolean includeUnmatchedRows, String idempotencyKey) {
}
