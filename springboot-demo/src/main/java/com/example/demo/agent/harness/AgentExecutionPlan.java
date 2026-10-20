package com.example.demo.agent.harness;

import com.example.demo.agent.model.AgentRoutingResult;

import java.util.List;
import java.util.Map;

public record AgentExecutionPlan(
        String status,
        List<AgentRoutingResult.Selection> selections,
        List<String> allowedTools,
        String instructionSnapshot,
        Map<String, String> instructionVersions,
        String message) {
    public AgentExecutionPlan {
        selections = selections == null ? List.of() : List.copyOf(selections);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        instructionSnapshot = instructionSnapshot == null ? "" : instructionSnapshot;
        instructionVersions = instructionVersions == null ? Map.of() : Map.copyOf(instructionVersions);
    }
}
