package com.example.demo.agent.harness;

import com.example.demo.agent.model.AgentChatMessage;
import com.example.demo.agent.model.AgentModelGateway;
import com.example.demo.agent.model.AgentRoutingResult;
import com.example.demo.agent.tool.AgentToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

@Component
public class AgentRoutingService {
    private static final Logger log = LoggerFactory.getLogger(AgentRoutingService.class);
    private final AgentCapabilityRegistry registry;
    private final AgentInstructionLoader instructionLoader;
    private final boolean enabled;

    public AgentRoutingService(AgentCapabilityRegistry registry,
                               AgentInstructionLoader instructionLoader,
                               @Value("${agent.routing.enabled:false}") boolean enabled) {
        this.registry = registry;
        this.instructionLoader = instructionLoader;
        this.enabled = enabled;
    }

    public AgentExecutionPlan plan(String displayInput, String modelInput, List<AgentChatMessage> history,
                                   AgentModelGateway gateway, List<AgentToolDefinition> allTools) {
        if (!enabled) {
            return new AgentExecutionPlan("READY", List.of(), allTools.stream().map(AgentToolDefinition::getName).toList(), "", java.util.Map.of(), null);
        }
        boolean hasAttachments = modelInput != null && !modelInput.equals(displayInput);
        String routingInput = (displayInput == null ? "" : displayInput)
                + (hasAttachments ? "\n[本轮含已解析附件；附件内容不提供给路由阶段]" : "");
        long startedAt = System.nanoTime();
        AgentRoutingResult routed = gateway.route(routingInput, recentHistory(history), registry.catalog(allTools));
        if (routed == null || routed.status() == null) throw new IllegalStateException("任务路由未返回结构化结果");
        String status = routed.status().toUpperCase();
        if ("CLARIFY".equals(status) || "UNSUPPORTED".equals(status)) {
            log.info("Agent routing finished status={} selectionCount={} elapsedMs={}", status,
                    routed.selections().size(), (System.nanoTime() - startedAt) / 1_000_000);
            return new AgentExecutionPlan(status, routed.selections(), List.of(), "", java.util.Map.of(), routed.message());
        }
        if (!"READY".equals(status) || routed.selections().isEmpty()) {
            throw new IllegalStateException("任务路由结果状态或能力选择无效");
        }
        Set<String> allowed = registry.toolsFor(routed.selections(), allTools);
        AgentInstructionLoader.LoadedInstructions instructions = instructionLoader.load(routed.selections());
        log.info("Agent routing finished status=READY capabilities={} toolCount={} instructionCount={} elapsedMs={}",
                routed.selections().size(), allowed.size(), instructions.versions().size(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return new AgentExecutionPlan(status, routed.selections(), List.copyOf(allowed),
                instructions.snapshot(), instructions.versions(), null);
    }

    private List<AgentChatMessage> recentHistory(List<AgentChatMessage> history) {
        if (history == null || history.isEmpty()) return List.of();
        return history.subList(Math.max(0, history.size() - 6), history.size()).stream()
                .map(message -> new AgentChatMessage(message.role(), truncate(message.content(), 500)))
                .toList();
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "…";
    }
}
