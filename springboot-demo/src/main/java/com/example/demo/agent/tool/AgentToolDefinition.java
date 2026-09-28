package com.example.demo.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class AgentToolDefinition {
    private String name;
    private String description;
    private AgentToolRisk risk;
    private Map<String, Object> inputSchema;
}
