package com.example.demo.agent.harness;

import com.example.demo.agent.model.AgentToolCall;
import com.example.demo.agent.tool.AgentPreparedTool;

import java.util.Map;

public record AgentApprovedTool(
        AgentToolCall call,
        Map<String, Object> arguments,
        AgentPreparedTool prepared) {
}
