package com.example.demo.excel.dto;

import java.util.List;

public record ExcelComparisonRequest(Long baseTaskId, Long baseTaskVersion,
                                     Long newTaskId, Long newTaskVersion,
                                     List<String> matchKeys, List<String> compareFields,
                                     String idempotencyKey) {
}
