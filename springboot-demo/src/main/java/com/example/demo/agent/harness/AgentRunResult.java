package com.example.demo.agent.harness;

import lombok.Data;

@Data
public class AgentRunResult {
    private Long runId;
    private Long conversationId;
    private AgentRunStatus status;
    private String output;
    private AgentPendingAction pendingAction;

    public AgentRunResult(Long runId, AgentRunStatus status, String output) {
        this(runId, status, output, null);
    }

    public AgentRunResult(
            Long runId,
            AgentRunStatus status,
            String output,
            AgentPendingAction pendingAction) {
        this.runId = runId;
        this.status = status;
        this.output = output;
        this.pendingAction = pendingAction;
    }
}
