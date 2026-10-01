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
@Table(name = "excel_task_sheet")
@Data
public class ExcelTaskSheet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long taskId;
    private Integer sheetIndex;
    private String sheetName;
    private Integer headerRowNumber;
    private Integer dataStartRowNumber;
    private Integer rowCount;
    private Integer columnCount;
    @Lob
    @Column(name = "columns_json")
    private String columnsJson;
}
