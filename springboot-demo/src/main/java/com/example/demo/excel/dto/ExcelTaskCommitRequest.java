package com.example.demo.excel.dto;

public record ExcelTaskCommitRequest(Long expectedVersion, String idempotencyKey) {
}
