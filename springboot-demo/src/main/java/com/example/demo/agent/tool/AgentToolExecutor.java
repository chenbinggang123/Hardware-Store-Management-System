package com.example.demo.agent.tool;

import com.example.demo.agent.hook.AgentHookChain;
import com.example.demo.agent.hook.AgentHookContext;
import com.example.demo.agent.model.AgentToolCall;
import com.example.demo.agent.policy.AgentPolicyDecision;
import com.example.demo.agent.policy.AgentPolicyEngine;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentToolExecutor {
    private final AgentToolRegistry registry;
    private final AgentPolicyEngine policyEngine;
    private final AgentHookChain hooks;

    public AgentToolExecutor(AgentToolRegistry registry,
                             AgentPolicyEngine policyEngine,
                             AgentHookChain hooks) {
        this.registry = registry;
        this.policyEngine = policyEngine;
        this.hooks = hooks;
    }

    public List<AgentToolDefinition> definitions() {
        return registry.definitions();
    }

    public AgentTool require(String name) {
        return registry.require(name);
    }

    public AgentPreparedTool prepare(Long runId, Long operatorId, AgentToolCall call) {
        return prepare(runId, operatorId, call, true);
    }

    public AgentPreparedTool prepareApproved(Long runId, Long operatorId, AgentToolCall call) {
        return prepare(runId, operatorId, call, false);
    }

    private AgentPreparedTool prepare(
            Long runId,
            Long operatorId,
            AgentToolCall call,
            boolean createApprovalPreview) {
        AgentTool tool = registry.require(call.getName());
        AgentToolContext executionContext = new AgentToolContext(runId, operatorId);
        AgentHookContext hookContext = new AgentHookContext(executionContext, call, tool.definition());
        hooks.preToolUse(hookContext);
        AgentPolicyDecision decision = policyEngine.evaluate(executionContext, tool, call.getArguments());
        Object preview = createApprovalPreview && decision == AgentPolicyDecision.REQUIRE_APPROVAL
                ? tool.approvalPreview(executionContext, call.getArguments()) : null;
        return new AgentPreparedTool(tool, hookContext, decision, preview);
    }

    public Object execute(AgentPreparedTool prepared) {
        long started = System.currentTimeMillis();
        try {
            Object result = prepared.tool().execute(
                    prepared.hookContext().executionContext(), prepared.hookContext().call().getArguments());
            hooks.postToolUse(prepared.hookContext(), result, System.currentTimeMillis() - started);
            return result;
        } catch (RuntimeException exception) {
            hooks.onToolError(prepared.hookContext(), exception, System.currentTimeMillis() - started);
            throw exception;
        }
    }
}
