package com.example.demo.agent;

import com.example.demo.agent.hook.AgentHookChain;
import com.example.demo.agent.hook.AgentHookContext;
import com.example.demo.agent.hook.AgentLifecycleHook;
import com.example.demo.agent.model.AgentToolCall;
import com.example.demo.agent.policy.AgentPolicyDecision;
import com.example.demo.agent.policy.AgentPolicyEngine;
import com.example.demo.agent.tool.AgentPreparedTool;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolExecutor;
import com.example.demo.agent.tool.AgentToolRegistry;
import com.example.demo.agent.tool.AgentToolRisk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolExecutorTest {

    @Test
    void executesLifecycleHooksAroundAllowedTool() {
        List<String> events = new ArrayList<>();
        AgentTool tool = tool("read_product", AgentToolRisk.R0_READ_ONLY,
                arguments -> Map.of("id", arguments.get("id")));
        AgentLifecycleHook hook = new AgentLifecycleHook() {
            @Override
            public void preToolUse(AgentHookContext context) {
                events.add("pre:" + context.call().getName());
            }

            @Override
            public void postToolUse(AgentHookContext context, Object result, long durationMs) {
                events.add("post:" + context.call().getName());
            }
        };
        AgentToolExecutor executor = executor(tool, hook);

        AgentPreparedTool prepared = executor.prepare(
                7L, 9L, new AgentToolCall("call-1", "read_product", Map.of("id", 3L)));
        Object result = executor.execute(prepared);

        assertEquals(AgentPolicyDecision.ALLOW, prepared.decision());
        assertEquals(Map.of("id", 3L), result);
        assertEquals(List.of("pre:read_product", "post:read_product"), events);
    }

    @Test
    void reportsToolFailureWithoutReplacingOriginalException() {
        IllegalStateException original = new IllegalStateException("库存服务不可用");
        List<String> events = new ArrayList<>();
        AgentTool tool = tool("read_inventory", AgentToolRisk.R0_READ_ONLY, arguments -> {
            throw original;
        });
        AgentLifecycleHook hook = new AgentLifecycleHook() {
            @Override
            public void onToolError(AgentHookContext context, RuntimeException error, long durationMs) {
                events.add("error:" + error.getMessage());
            }
        };
        AgentToolExecutor executor = executor(tool, hook);
        AgentPreparedTool prepared = executor.prepare(
                7L, 9L, new AgentToolCall("call-2", "read_inventory", Map.of()));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> executor.execute(prepared));

        assertSame(original, thrown);
        assertEquals(List.of("error:库存服务不可用"), events);
    }

    @Test
    void doesNotTurnSuccessfulToolIntoFailureWhenPostHookFails() {
        List<String> events = new ArrayList<>();
        AgentTool tool = tool("read_product", AgentToolRisk.R0_READ_ONLY, arguments -> "ok");
        AgentLifecycleHook failingHook = new AgentLifecycleHook() {
            @Override
            public void postToolUse(AgentHookContext context, Object result, long durationMs) {
                throw new IllegalStateException("指标服务不可用");
            }
        };
        AgentLifecycleHook followingHook = new AgentLifecycleHook() {
            @Override
            public void postToolUse(AgentHookContext context, Object result, long durationMs) {
                events.add("following-hook");
            }
        };
        AgentToolExecutor executor = new AgentToolExecutor(
                new AgentToolRegistry(List.of(tool)),
                new AgentPolicyEngine(),
                new AgentHookChain(List.of(failingHook, followingHook)));
        AgentPreparedTool prepared = executor.prepare(
                7L, 9L, new AgentToolCall("call-4", "read_product", Map.of()));

        Object result = executor.execute(prepared);

        assertEquals("ok", result);
        assertEquals(List.of("following-hook"), events);
    }

    @Test
    void createsApprovalPreviewWithoutExecutingWriteTool() {
        AtomicBoolean executed = new AtomicBoolean(false);
        AgentTool tool = new AgentTool() {
            @Override
            public AgentToolDefinition definition() {
                return new AgentToolDefinition(
                        "create_product", "新增商品", AgentToolRisk.R2_WRITE, Map.of());
            }

            @Override
            public Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
                return Map.of("name", arguments.get("name"));
            }

            @Override
            public Object execute(AgentToolContext context, Map<String, Object> arguments) {
                executed.set(true);
                return Map.of("created", true);
            }
        };
        AgentToolExecutor executor = executor(tool, new AgentLifecycleHook() {
        });

        AgentPreparedTool prepared = executor.prepare(
                8L, 10L, new AgentToolCall("call-3", "create_product", Map.of("name", "电钻")));

        assertEquals(AgentPolicyDecision.REQUIRE_APPROVAL, prepared.decision());
        assertEquals(Map.of("name", "电钻"), prepared.approvalPreview());
        assertFalse(executed.get());

        AgentPreparedTool approved = executor.prepareApproved(
                8L, 10L, new AgentToolCall("call-3", "create_product", Map.of("name", "电钻")));
        assertEquals(AgentPolicyDecision.REQUIRE_APPROVAL, approved.decision());
        assertNull(approved.approvalPreview());
        assertFalse(executed.get());
    }

    private AgentToolExecutor executor(AgentTool tool, AgentLifecycleHook hook) {
        return new AgentToolExecutor(
                new AgentToolRegistry(List.of(tool)),
                new AgentPolicyEngine(),
                new AgentHookChain(List.of(hook)));
    }

    private AgentTool tool(
            String name,
            AgentToolRisk risk,
            java.util.function.Function<Map<String, Object>, Object> action) {
        return new AgentTool() {
            @Override
            public AgentToolDefinition definition() {
                return new AgentToolDefinition(name, name, risk, Map.of());
            }

            @Override
            public Object execute(AgentToolContext context, Map<String, Object> arguments) {
                return action.apply(arguments);
            }
        };
    }
}
