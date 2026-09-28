package com.example.demo.agent.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class AgentToolCall {
    private String id;
    private String name;
    private Map<String, Object> arguments;
}
