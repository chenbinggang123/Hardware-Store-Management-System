package com.example.demo.agent.tool;

import lombok.Data;

import java.util.Map;

@Data
public class AgentToolDefinition {
    private String name;
    private String description;
    private AgentToolRisk risk;
    private Map<String, Object> inputSchema;
    private String approvalTitle;

    public AgentToolDefinition(
            String name,
            String description,
            AgentToolRisk risk,
            Map<String, Object> inputSchema) {
        this(name, description, risk, inputSchema, null);
    }

    public AgentToolDefinition(
            String name,
            String description,
            AgentToolRisk risk,
            Map<String, Object> inputSchema,
            String approvalTitle) {
        this.name = name;
        this.description = description;
        this.risk = risk;
        this.inputSchema = inputSchema;
        this.approvalTitle = approvalTitle;
    }
}
