package com.example.demo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "factory_ledger_settlement")
@Data
public class FactoryLedgerSettlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long ledgerId;
    private String settlementMonth;
    private String status;
    private Long operatorId;
    private LocalDateTime updateTime;
    @Version
    private Long version;
}
