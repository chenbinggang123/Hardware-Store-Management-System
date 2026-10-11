package com.example.demo.agent.entity;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SourcePurchaseItem {
    private Integer lineNo;
    private Long productId;
    private String barcode;
    private String productName;
    private String specification;
    private String unit;
    private BigDecimal quantity;
    private BigDecimal listUnitPrice;
    private BigDecimal discount;
    private BigDecimal netUnitPrice;
    private BigDecimal lineAmount;
    private String matchStatus;
    private String remark;
}
