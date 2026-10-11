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
    /** 文件任务 ID；对话中直接生成的文件没有任务，允许为空。 */
    private Long taskId;
    private Long operatorId;
    /** EXCEL_TASK：文件任务输出；AGENT_WORKBOOK：小五按对话内容生成的 Excel。 */
    private String sourceType = "EXCEL_TASK";
    /** 对话生成文件时记录来源附件 ID。 */
    private Long sourceId;
    private Long runId;
    private Long conversationId;
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
