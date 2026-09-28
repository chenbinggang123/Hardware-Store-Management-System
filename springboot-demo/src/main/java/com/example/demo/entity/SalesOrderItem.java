package com.example.demo.entity;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 销售单明细
 */
@Data
public class SalesOrderItem {
    private Long productId;
    private String productName;
    private Integer quantity;
    private BigDecimal price;
    private BigDecimal amount;
}
