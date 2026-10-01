package com.example.demo.agent.tool;

import com.example.demo.agent.hook.AgentHookContext;
import com.example.demo.agent.policy.AgentPolicyDecision;

public record AgentPreparedTool(AgentTool tool,
                                AgentHookContext hookContext,
                                AgentPolicyDecision decision,
                                Object approvalPreview) {
}
