package com.example.demo.excel.dto;

import java.util.Map;

public record ExcelComparisonItemView(Long id, String changeType, String matchKey,
                                      Long productId, String productName,
                                      Integer baseRowNumber, Integer newRowNumber,
                                      Map<String, Object> changes,
                                      String issueCode, String issueMessage) {
}
