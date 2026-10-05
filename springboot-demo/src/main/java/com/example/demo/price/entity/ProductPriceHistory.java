package com.example.demo.price.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_price_history")
@Data
public class ProductPriceHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long productId;
    private Long supplierId;
    private String priceType;
    private String eventType;
    private BigDecimal beforePrice;
    private BigDecimal afterPrice;
    private BigDecimal changePercent;
    private Boolean appliedToProduct;
    private String sourceType;
    private Long sourceTaskId;
    private Long sourceRecordId;
    private String sourceLineKey;
    private Long operatorId;
    private LocalDate effectiveDate;
    private String reason;
    private String idempotencyKey;
    private LocalDateTime createTime;
}
