package com.example.demo.agent.harness;

import com.example.demo.agent.tool.AgentToolRisk;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AgentPendingAction {
    private String toolCallId;
    private String toolName;
    private AgentToolRisk risk;
    private String title;
    private String description;
    private Object preview;
    private LocalDateTime expiresAt;
}
