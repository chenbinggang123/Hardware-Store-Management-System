package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.service.SourcePurchaseOrderService;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SearchSourcePurchaseOrdersTool implements AgentTool {
    private final SourcePurchaseOrderService sourcePurchaseOrderService;

    public SearchSourcePurchaseOrdersTool(SourcePurchaseOrderService sourcePurchaseOrderService) {
        this.sourcePurchaseOrderService = sourcePurchaseOrderService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "search_source_purchase_orders",
                "按厂商名称、厂商单号或凭证 ID 查询已保存的源头厂商采购凭证",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of("keyword", Map.of("type", "string", "description", "搜索关键词；留空查询最近记录")),
                        "required", List.of()));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("keyword");
        return sourcePurchaseOrderService.search(value == null ? null : value.toString().trim());
    }
}
