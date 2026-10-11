package com.example.demo.agent.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class SourcePurchaseOrderRequest {
    private Long attachmentId;
    private String supplierName;
    private String sourceOrderNo;
    private LocalDate orderDate;
    private String buyerName;
    private String handlerName;
    private BigDecimal declaredTotalAmount;
    private Boolean receiveStock = false;
    private String remark;
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        private Integer lineNo;
        private String barcode;
        private String productName;
        private String specification;
        private String unit;
        private BigDecimal quantity;
        private BigDecimal listUnitPrice;
        private BigDecimal discount;
        private BigDecimal netUnitPrice;
        private BigDecimal lineAmount;
        private String remark;
    }
}
