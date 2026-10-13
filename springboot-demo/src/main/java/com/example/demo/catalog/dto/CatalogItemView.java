package com.example.demo.catalog.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record CatalogItemView(
        Long id,
        String name,
        String barcode,
        String spec,
        String unit,
        BigDecimal retailPrice,
        BigDecimal wholesalePrice,
        BigDecimal oldCustomerPrice,
        String sourceFactory,
        String imageUrl,
        Long inventoryId,
        Integer quantity,
        Integer warningThreshold,
        String locationId,
        String stockState,
        boolean inventoryRecorded,
        boolean lowStock,
        LocalDateTime lastSoldAt,
        LocalDateTime lastPurchasedAt,
        LocalDateTime lastActivityAt,
        int activityCount,
        List<String> attentionReasons) {
}
