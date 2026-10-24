package com.example.demo.agent.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_attachment")
@Data
public class AgentAttachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long conversationId;
    private Long runId;
    private Long operatorId;
    private String objectKey;
    private String originalName;
    private String mimeType;
    private Long fileSize;
    private String sha256;
    private String parseStatus;
    @Lob
    private String extractedText;
    private String errorMessage;
    /** OCR 行位置 JSON，开单页用来截取原图上的一行。 */
    @Lob
    private String ocrLayout;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
