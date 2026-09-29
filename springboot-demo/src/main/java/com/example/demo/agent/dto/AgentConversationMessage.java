package com.example.demo.agent.dto;

import com.example.demo.agent.harness.AgentRunStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AgentConversationMessage {
    private Long runId;
    private String role;
    private String content;
    private AgentRunStatus status;
    private List<AgentAttachmentResponse> attachments;

    public AgentConversationMessage(Long runId, String role, String content, AgentRunStatus status) {
        this(runId, role, content, status, List.of());
    }
}
