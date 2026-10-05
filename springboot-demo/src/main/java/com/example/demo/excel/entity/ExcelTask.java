package com.example.demo.excel.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "excel_task")
@Data
public class ExcelTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long operatorId;
    private Long supplierId;
    private LocalDate priceEffectiveDate;
    private String purpose;
    private String status;
    private String originalName;
    private String objectKey;
    private String mimeType;
    private Long fileSize;
    private String sha256;
    private Integer sheetCount;
    private Integer rowCount;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @Version
    private Long version;
}
