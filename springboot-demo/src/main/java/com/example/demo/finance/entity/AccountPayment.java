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
@Table(name = "account_payment")
@Data
public class AccountPayment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long operatorId;
    private String direction;
    private String counterpartyType;
    private Long counterpartyId;
    private String counterpartyName;
    private LocalDate paymentDate;
    private BigDecimal amount;
    private BigDecimal unallocatedAmount;
    private String paymentMethod;
    private String note;
    private LocalDateTime createTime;
}
