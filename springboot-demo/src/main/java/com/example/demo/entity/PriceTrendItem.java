package com.example.demo.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 供应商价格趋势项
 */
@Data
public class PriceTrendItem {
    private Long supplierId;
    private Long productId;
    private String productName;
    private BigDecimal price;
    private String orderNumber;
    private LocalDateTime orderTime;
}
