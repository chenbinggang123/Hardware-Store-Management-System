package com.example.demo.agent.hook;

public interface AgentLifecycleHook {
    default void preToolUse(AgentHookContext context) {
    }

    default void postToolUse(AgentHookContext context, Object result, long durationMs) {
    }

    default void onToolError(AgentHookContext context, RuntimeException error, long durationMs) {
    }
}
