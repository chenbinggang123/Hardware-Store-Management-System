package com.example.demo.agent.model;

import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.tool.AgentToolDefinition;

import java.util.List;

public interface AgentModelGateway {
    AgentModelResponse respond(AgentRunContext context, List<AgentToolDefinition> tools);

    default AgentModelResponse respondStreaming(
            AgentRunContext context,
            List<AgentToolDefinition> tools,
            AgentModelStreamListener listener) {
        AgentModelResponse response = respond(context, tools);
        if (response != null && response.hasFinalOutput()) {
            listener.onDelta(response.getFinalOutput());
        }
        return response;
    }
}
