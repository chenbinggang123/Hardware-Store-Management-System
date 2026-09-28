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
@Table(name = "agent_tool_call")
@Data
public class AgentToolCallTrace {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long runId;
    private String toolCallId;
    private String toolName;
    @Lob
    private String argumentsJson;
    @Lob
    private String resultJson;
    private String status;
    private Long durationMs;
    private LocalDateTime createTime;
}
