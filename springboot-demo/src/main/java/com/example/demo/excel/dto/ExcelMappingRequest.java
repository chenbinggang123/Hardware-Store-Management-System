package com.example.demo.excel.dto;

import java.util.List;

public record ExcelMappingRequest(Long expectedVersion, List<ExcelColumnMappingRequest> mappings) {
}
