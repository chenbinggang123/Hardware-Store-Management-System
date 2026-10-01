package com.example.demo.agent.hook;

import com.example.demo.agent.model.AgentToolCall;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;

public record AgentHookContext(AgentToolContext executionContext,
                               AgentToolCall call,
                               AgentToolDefinition definition) {
}
