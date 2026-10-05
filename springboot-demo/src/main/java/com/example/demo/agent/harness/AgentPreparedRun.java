package com.example.demo.agent.harness;

import com.example.demo.agent.entity.AgentRun;

public record AgentPreparedRun(
        AgentRun run,
        AgentRunContext context,
        AgentRunResult initialResult) {
}
