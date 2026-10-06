package com.example.demo.excel.dto;

public record ExcelQuoteRequest(Long expectedVersion, String pricingMode, Long customerId,
                                boolean includeUnmatchedRows, String idempotencyKey, String outputFormat) {
    public ExcelQuoteRequest(Long expectedVersion, String pricingMode, Long customerId,
                             boolean includeUnmatchedRows, String idempotencyKey) {
        this(expectedVersion, pricingMode, customerId, includeUnmatchedRows, idempotencyKey, "XLSX");
    }
}
