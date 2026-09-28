package com.example.demo.agent.entity;

import com.example.demo.agent.harness.AgentRunStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_run")
@Data
public class AgentRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long operatorId;
    @Enumerated(EnumType.STRING)
    private AgentRunStatus status;
    private Integer currentStep;
    @Lob
    private String inputText;
    @Lob
    private String outputText;
    @Lob
    private String contextJson;
    private String pendingToolCallId;
    private String pendingToolName;
    @Lob
    private String pendingArgumentsJson;
    @Lob
    private String pendingPreviewJson;
    private String errorCode;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime expiresAt;
    @Version
    private Long version;
}
