package com.example.demo.excel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "excel_task_row_result")
@Data
public class ExcelTaskRowResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long taskId;
    private Long sheetId;
    private Long rowId;
    @Column(name = "source_row_number")
    private Integer rowNumber;
    private String matchStatus;
    private Long matchedProductId;
    private String matchReason;
    private String actionType;
    @Lob
    private String normalizedJson;
    @Lob
    private String candidatesJson;
    @Lob
    private String beforeJson;
    @Lob
    private String afterJson;
    @Lob
    private String issuesJson;
}
