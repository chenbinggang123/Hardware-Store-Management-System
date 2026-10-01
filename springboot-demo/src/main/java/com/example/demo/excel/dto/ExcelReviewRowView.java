package com.example.demo.excel.dto;

import java.util.List;
import java.util.Map;

public record ExcelReviewRowView(Long id, Long sourceRowId, int rowNumber, String matchStatus, Long matchedProductId,
                                 String matchReason, String actionType, Map<String, Object> normalized,
                                 List<ProductCandidateView> candidates, Map<String, Object> before,
                                 Map<String, Object> after, List<String> issues) {
}
