package com.example.demo.agent.hook;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentHookChain {
    private static final Logger log = LoggerFactory.getLogger(AgentHookChain.class);
    private final List<AgentLifecycleHook> hooks;

    public AgentHookChain(List<AgentLifecycleHook> hooks) {
        List<AgentLifecycleHook> ordered = new ArrayList<>(hooks == null ? List.of() : hooks);
        AnnotationAwareOrderComparator.sort(ordered);
        this.hooks = List.copyOf(ordered);
    }

    public void preToolUse(AgentHookContext context) {
        hooks.forEach(hook -> hook.preToolUse(context));
    }

    public void postToolUse(AgentHookContext context, Object result, long durationMs) {
        for (AgentLifecycleHook hook : hooks) {
            try {
                hook.postToolUse(context, result, durationMs);
            } catch (RuntimeException exception) {
                log.warn("Agent PostToolUse Hook 执行失败，tool={}, hook={}",
                        context.call().getName(), hook.getClass().getName(), exception);
            }
        }
    }

    public void onToolError(AgentHookContext context, RuntimeException error, long durationMs) {
        RuntimeException hookFailure = null;
        for (AgentLifecycleHook hook : hooks) {
            try {
                hook.onToolError(context, error, durationMs);
            } catch (RuntimeException exception) {
                if (hookFailure == null) hookFailure = exception;
                else hookFailure.addSuppressed(exception);
            }
        }
        if (hookFailure != null) error.addSuppressed(hookFailure);
    }
}
