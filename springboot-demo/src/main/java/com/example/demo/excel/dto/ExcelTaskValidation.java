package com.example.demo.excel.dto;

import java.util.List;

public record ExcelTaskValidation(Long taskId, String status, long version, boolean valid,
                                  int totalRows, int includedRows, int excludedRows,
                                  int matchedRows, int readyToCreateRows,
                                  int needsReviewRows, int invalidRows, List<String> blockers) {
}
