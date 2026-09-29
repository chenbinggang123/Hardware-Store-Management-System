package com.example.demo.agent.dto;

import lombok.Data;

@Data
public class CloudAttachmentRequest {
    private String cloudFileId;
    private String downloadUrl;
    private String originalName;
    private Long fileSize;
    private Long conversationId;
}
