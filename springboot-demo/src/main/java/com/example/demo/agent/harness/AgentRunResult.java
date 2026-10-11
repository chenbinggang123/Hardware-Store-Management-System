package com.example.demo.agent.harness;

import com.example.demo.agent.dto.AgentFileView;
import lombok.Data;

import java.util.List;

@Data
public class AgentRunResult {
    private Long runId;
    private Long conversationId;
    private AgentRunStatus status;
    private String output;
    private AgentPendingAction pendingAction;
    /** Files the agent generated in this run, so the client can show download cards without parsing text. */
    private List<AgentFileView> files = List.of();

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
