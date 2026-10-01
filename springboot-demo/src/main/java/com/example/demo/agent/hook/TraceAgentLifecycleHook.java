package com.example.demo.agent.hook;

import com.example.demo.agent.trace.AgentTraceRecorder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TraceAgentLifecycleHook implements AgentLifecycleHook {
    private final AgentTraceRecorder traceRecorder;

    public TraceAgentLifecycleHook(AgentTraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    @Override
    public void postToolUse(AgentHookContext context, Object result, long durationMs) {
        traceRecorder.record(context.executionContext().getRunId(), context.call(), result, "SUCCESS", durationMs);
    }

    @Override
    public void onToolError(AgentHookContext context, RuntimeException error, long durationMs) {
        traceRecorder.record(context.executionContext().getRunId(), context.call(), error.getMessage(), "FAILED", durationMs);
    }
}
