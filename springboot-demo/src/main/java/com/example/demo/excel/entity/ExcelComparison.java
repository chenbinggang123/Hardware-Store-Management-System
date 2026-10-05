package com.example.demo.excel.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "excel_comparison")
@Data
public class ExcelComparison {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long operatorId;
    private Long baseTaskId;
    private Long baseTaskVersion;
    private Long newTaskId;
    private Long newTaskVersion;
    private String status;
    private String matchKeysJson;
    private String compareFieldsJson;
    private String requestDigest;
    private String idempotencyKey;
    @Lob
    private String summaryJson;
    private Integer progressCompleted;
    private Integer progressTotal;
    private Integer issueCount;
    private LocalDateTime cancelRequestedAt;
    private String errorCode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
