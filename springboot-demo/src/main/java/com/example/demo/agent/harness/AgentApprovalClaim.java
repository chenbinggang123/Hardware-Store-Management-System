package com.example.demo.agent.harness;

import com.example.demo.agent.entity.AgentRun;

public record AgentApprovalClaim(AgentRun run, AgentRunResult terminalResult) {
    public boolean isTerminal() {
        return terminalResult != null;
    }
}
