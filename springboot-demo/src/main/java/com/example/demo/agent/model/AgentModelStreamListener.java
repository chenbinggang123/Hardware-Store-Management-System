package com.example.demo.agent.model;

@FunctionalInterface
public interface AgentModelStreamListener {
    AgentModelStreamListener NONE = delta -> { };

    void onDelta(String delta);
}
