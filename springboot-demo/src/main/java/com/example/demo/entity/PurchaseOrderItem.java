package com.example.demo.entity;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 采购单明细
 */
@Data
public class PurchaseOrderItem {
    private Long productId;
    private String productName;
    private Integer quantity;
    private BigDecimal price;
    private BigDecimal amount;
    private String locationId;
}
