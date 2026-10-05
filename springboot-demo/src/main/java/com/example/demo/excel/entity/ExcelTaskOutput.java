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
@Table(name = "excel_task_output")
@Data
public class ExcelTaskOutput {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long taskId;
    private Long operatorId;
    private String outputType;
    private String objectKey;
    private String originalName;
    private String mimeType;
    private Long fileSize;
    private String sha256;
    private String status;
    private String requestDigest;
    private String idempotencyKey;
    @Lob
    private String summaryJson;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime expiresAt;
}
