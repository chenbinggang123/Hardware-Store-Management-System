package com.example.demo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class SalesDraftLinePreview {
    private Long productId;
    private String productName;
    private String specification;
    private Integer quantity;
    private Integer availableQuantity;
    private BigDecimal price;
    private BigDecimal amount;
    private boolean inventorySufficient;
}
