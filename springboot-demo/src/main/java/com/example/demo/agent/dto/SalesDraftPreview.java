package com.example.demo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class SalesDraftPreview {
    private Long draftId;
    private Long version;
    private String status;
    private Long customerId;
    private String customerName;
    private BigDecimal previousDebt;
    private BigDecimal totalAmount;
    private BigDecimal receivedAmount;
    private BigDecimal newDebtAmount;
    private BigDecimal debtAfterCommit;
    private boolean inventorySufficient;
    private List<SalesDraftLinePreview> items;
    private LocalDateTime expiresAt;
}
