package com.example.demo.agent.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public AgentToolRegistry(List<AgentTool> registeredTools) {
        for (AgentTool tool : registeredTools) {
            String name = tool.definition().getName();
            if (tools.put(name, tool) != null) {
                throw new IllegalStateException("Agent 工具名称重复：" + name);
            }
        }
    }

    public AgentTool require(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("不允许调用未知工具：" + name);
        }
        return tool;
    }

    public List<AgentToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }
}
