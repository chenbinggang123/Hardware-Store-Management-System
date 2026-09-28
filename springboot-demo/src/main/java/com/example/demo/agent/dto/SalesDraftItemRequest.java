package com.example.demo.agent.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SalesDraftItemRequest {
    private Long productId;
    private Integer quantity;
    private BigDecimal price;
}
