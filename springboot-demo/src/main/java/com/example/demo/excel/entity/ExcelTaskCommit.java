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
@Table(name = "excel_task_commit")
@Data
public class ExcelTaskCommit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long taskId;
    private Long operatorId;
    private String idempotencyKey;
    private Long requestVersion;
    private String status;
    @Lob
    private String summaryJson;
    private LocalDateTime createTime;
}
