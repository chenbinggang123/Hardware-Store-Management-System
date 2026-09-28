package com.example.demo.agent.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class SalesDraftRequest {
    private Long draftVersion;
    private Long customerId;
    private BigDecimal receivedAmount;
    private List<SalesDraftItemRequest> items;
}
