package com.example.demo.agent.dto;

import lombok.Data;

import java.util.List;

@Data
public class AgentMessageRequest {
    private String content;
    private Long conversationId;
    private List<Long> attachmentIds;
}
