package com.example.demo.excel.dto;

public record ExcelWorkbookExportRequest(Long expectedVersion, String idempotencyKey) { }
