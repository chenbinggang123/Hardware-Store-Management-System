package com.example.demo.excel.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "excel_task_mapping")
@Data
public class ExcelTaskMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long taskId;
    private Long sheetId;
    private Integer sourceColumnIndex;
    private String sourceColumnName;
    private String targetField;
    private LocalDateTime createTime;
}
