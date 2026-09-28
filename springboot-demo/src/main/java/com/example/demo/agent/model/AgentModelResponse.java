package com.example.demo.agent.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AgentModelResponse {
    private String finalOutput;
    private List<AgentToolCall> toolCalls;

    public static AgentModelResponse finalAnswer(String output) {
        return new AgentModelResponse(output, List.of());
    }

    public static AgentModelResponse tools(List<AgentToolCall> calls) {
        return new AgentModelResponse(null, calls);
    }

    public boolean hasFinalOutput() {
        return finalOutput != null;
    }
}
