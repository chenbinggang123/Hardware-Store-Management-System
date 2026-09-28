package com.example.demo.agent.harness;

public enum AgentRunStatus {
    CREATED,
    RUNNING,
    WAITING_USER_INPUT,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED
}
