package com.example.demo.agent.tool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AgentToolRegistry {
    /**
     * 已停用的工具：开单只用快捷开单，记账只用工厂账本，商品不再有上下架。
     * 保留代码便于以后恢复，但不再交给模型，避免同一句话被记进两套账。
     * 可用 AGENT_RETIRED_TOOLS 覆盖（逗号分隔，留空表示全部启用）。
     */
    static final String DEFAULT_RETIRED_TOOLS = "create_sales_draft,update_sales_draft,commit_sales_draft,get_sales_draft,"
            + "create_account_document,record_account_payment,query_accounts,change_product_status";

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public AgentToolRegistry(List<AgentTool> registeredTools) {
        this(registeredTools, Set.of());
    }

    @Autowired
    public AgentToolRegistry(List<AgentTool> registeredTools,
                             @Value("${agent.tools.retired:" + DEFAULT_RETIRED_TOOLS + "}") String retiredTools) {
        this(registeredTools, parse(retiredTools));
    }

    private AgentToolRegistry(List<AgentTool> registeredTools, Set<String> retired) {
        for (AgentTool tool : registeredTools) {
            String name = tool.definition().getName();
            if (retired.contains(name)) {
                continue;
            }
            if (tools.put(name, tool) != null) {
                throw new IllegalStateException("Agent 工具名称重复：" + name);
            }
        }
    }

    private static Set<String> parse(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(name -> !name.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public AgentTool require(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("不允许调用未知工具：" + name);
        }
        return tool;
    }

    public List<AgentToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }
}
