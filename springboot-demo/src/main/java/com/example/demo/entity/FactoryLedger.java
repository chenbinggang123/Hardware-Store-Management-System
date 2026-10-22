package com.example.demo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "factory_ledger")
@Data
public class FactoryLedger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String ledgerNo;
    private String factoryName;
    private String contactPerson;
    private String phone;
    private LocalDate startDate;
    private Integer settlementDay;
    private Long openingBalanceCents;
    private String remark;
    private String status;
    /** CUSTOMER = 买的（工厂来拿货，我们应收）；SUPPLIER = 进货的（我们向源头厂商进货，我们应付）。 */
    private String kind;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
