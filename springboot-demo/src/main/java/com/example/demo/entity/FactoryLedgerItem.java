package com.example.demo.entity;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class FactoryLedgerItem {
    private String productName;
    private String specification;
    private String unit;
    private BigDecimal quantity;
    private Long unitPriceCents;
}
