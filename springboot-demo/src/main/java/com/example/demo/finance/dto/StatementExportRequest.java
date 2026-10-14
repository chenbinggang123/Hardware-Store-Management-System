package com.example.demo.finance.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class StatementExportRequest {
    private String direction;
    private Long counterpartyId;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private String idempotencyKey;
}
