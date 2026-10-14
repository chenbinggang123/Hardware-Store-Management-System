package com.example.demo.finance.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CounterpartyPaymentRequest {
    private String direction;
    private Long counterpartyId;
    private BigDecimal amount;
    private LocalDate paymentDate;
    private String paymentMethod;
    private String note;
}
