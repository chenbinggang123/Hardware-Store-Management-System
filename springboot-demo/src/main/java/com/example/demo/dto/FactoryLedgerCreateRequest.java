package com.example.demo.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class FactoryLedgerCreateRequest {
    private String factoryName;
    private String contactPerson;
    private String phone;
    private LocalDate startDate;
    private Integer settlementDay;
    private BigDecimal openingBalance;
    private String remark;
}
