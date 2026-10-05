package com.example.demo.excel.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "excel_comparison_item")
@Data
public class ExcelComparisonItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long comparisonId;
    private String changeType;
    private String matchKey;
    private Long productId;
    private String productName;
    private Integer baseRowNumber;
    private Integer newRowNumber;
    @Lob
    private String changesJson;
    private String issueCode;
    private String issueMessage;
}
