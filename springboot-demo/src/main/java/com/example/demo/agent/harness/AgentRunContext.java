package com.example.demo.agent.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
public class AgentRunContext {
    private final Long runId;
    private final Long operatorId;
    private final String userInput;
    private final List<Map<String, Object>> toolResults = new ArrayList<>();

    public AgentRunContext(Long runId, Long operatorId, String userInput) {
        this.runId = runId;
        this.operatorId = operatorId;
        this.userInput = userInput;
    }

    public void addToolResult(String toolCallId, String toolName, Map<String, Object> arguments, Object result) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("toolCallId", toolCallId);
        entry.put("toolName", toolName);
        entry.put("arguments", arguments);
        entry.put("result", result);
        toolResults.add(entry);
    }
}
