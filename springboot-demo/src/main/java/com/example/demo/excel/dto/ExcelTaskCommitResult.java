package com.example.demo.excel.dto;

import java.time.LocalDateTime;

public record ExcelTaskCommitResult(Long commitId, Long taskId, String status,
                                    int createdProducts, int updatedProducts,
                                    int inventoryChanges, int excludedRows,
                                    LocalDateTime committedAt) {
}
