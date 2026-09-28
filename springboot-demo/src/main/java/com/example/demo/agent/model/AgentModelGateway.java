package com.example.demo.agent.model;

import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.tool.AgentToolDefinition;

import java.util.List;

public interface AgentModelGateway {
    AgentModelResponse respond(AgentRunContext context, List<AgentToolDefinition> tools);
}
