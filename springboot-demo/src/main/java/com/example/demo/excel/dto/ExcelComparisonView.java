package com.example.demo.excel.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record ExcelComparisonView(Long id, Long baseTaskId, long baseTaskVersion,
                                  Long newTaskId, long newTaskVersion, String status,
                                  int completed, int total, int issueCount,
                                  Map<String, Object> summary, String errorCode,
                                  LocalDateTime createTime, LocalDateTime updateTime) {
}
