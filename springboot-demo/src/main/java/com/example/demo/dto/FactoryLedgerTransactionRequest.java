package com.example.demo.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class FactoryLedgerTransactionRequest {
    private String type;
    private LocalDate date;
    private String receiptNo;
    private BigDecimal paymentAmount;
    private String remark;
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        private String productName;
        private String specification;
        private String unit;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
    }
}
