package com.example.demo.excel.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ExcelTaskCloudRequest {
    private String cloudFileId;
    private String downloadUrl;
    private String originalName;
    private Long fileSize;
    private String purpose;
    private Long supplierId;
    private LocalDate priceEffectiveDate;
}
