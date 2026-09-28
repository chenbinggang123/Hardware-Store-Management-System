package com.example.demo.agent.tool;

import java.util.Map;

public interface AgentTool {
    AgentToolDefinition definition();

    default Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
        return arguments;
    }

    Object execute(AgentToolContext context, Map<String, Object> arguments);
}
