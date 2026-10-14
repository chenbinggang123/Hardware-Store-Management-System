package com.example.demo.finance.dto;

import java.math.BigDecimal;

public record AccountSyncPreview(int salesOrderCount, BigDecimal salesAmount, int purchaseOrderCount,
                                 BigDecimal purchaseAmount, int alreadySyncedCount) {
}
