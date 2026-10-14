package com.example.demo.finance.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "account_document")
@Data
public class AccountDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long operatorId;
    private String direction;
    private String counterpartyType;
    private Long counterpartyId;
    private String counterpartyName;
    private String documentNumber;
    private LocalDate documentDate;
    private LocalDate dueDate;
    private BigDecimal totalAmount;
    private BigDecimal settledAmount;
    private BigDecimal outstandingAmount;
    private String status;
    private String note;
    private String fileSourceKind;
    private Long fileSourceId;
    private String businessSourceType;
    private Long businessSourceId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
