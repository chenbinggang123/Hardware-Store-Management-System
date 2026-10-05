package com.example.demo.price.dto;

import com.example.demo.price.entity.ProductPriceHistory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PriceHistoryItem(
        Long id, Long productId, Long supplierId, String priceType, String eventType,
        BigDecimal beforePrice, BigDecimal afterPrice, BigDecimal changePercent,
        boolean appliedToProduct, String sourceType, Long sourceTaskId,
        Long sourceRecordId, String sourceLineKey, Long operatorId,
        LocalDate effectiveDate, String reason, LocalDateTime createTime) {
    public static PriceHistoryItem from(ProductPriceHistory value) {
        return new PriceHistoryItem(
                value.getId(), value.getProductId(), value.getSupplierId(), value.getPriceType(),
                value.getEventType(), value.getBeforePrice(), value.getAfterPrice(), value.getChangePercent(),
                Boolean.TRUE.equals(value.getAppliedToProduct()), value.getSourceType(), value.getSourceTaskId(),
                value.getSourceRecordId(), value.getSourceLineKey(), value.getOperatorId(), value.getEffectiveDate(),
                value.getReason(), value.getCreateTime());
    }
}
