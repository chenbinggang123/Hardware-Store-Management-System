package com.example.demo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentAttachmentResponse {
    private Long id;
    private Long conversationId;
    private String originalName;
    private String mimeType;
    private Long fileSize;
    private String parseStatus;
    private String errorMessage;
}
