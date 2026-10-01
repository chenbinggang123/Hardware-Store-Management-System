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
@Table(name = "excel_task_row")
@Data
public class ExcelTaskRow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long taskId;
    private Long sheetId;
    @Column(name = "source_row_number")
    private Integer rowNumber;
    private String reviewStatus;
    @Lob
    @Column(name = "cells_json")
    private String cellsJson;
}
