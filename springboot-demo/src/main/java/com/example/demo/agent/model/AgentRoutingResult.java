package com.example.demo.agent.model;

import java.util.List;

public record AgentRoutingResult(String status, List<Selection> selections, String message) {
    public record Selection(String capabilityId, String operation) {}

    public AgentRoutingResult {
        selections = selections == null ? List.of() : List.copyOf(selections);
    }
}
