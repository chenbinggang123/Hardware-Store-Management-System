package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.service.SalesDraftService;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GetSalesDraftTool implements AgentTool {
    private final SalesDraftService salesDraftService;

    public GetSalesDraftTool(SalesDraftService salesDraftService) {
        this.salesDraftService = salesDraftService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "get_sales_draft",
                "读取当前操作人名下销售草稿的完整内容、版本、金额、欠款影响和库存情况",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of("draftId", Map.of("type", "integer", "description", "销售草稿 ID")),
                        "required", List.of("draftId")));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("draftId");
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("draftId 必须是整数");
        }
        return salesDraftService.getDraft(number.longValue(), context.getOperatorId());
    }
}
