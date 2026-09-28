package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.dto.SalesDraftRequest;
import com.example.demo.agent.service.SalesDraftService;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CreateSalesDraftTool implements AgentTool {
    private final SalesDraftService salesDraftService;
    private final ObjectMapper objectMapper;

    public CreateSalesDraftTool(SalesDraftService salesDraftService, ObjectMapper objectMapper) {
        this.salesDraftService = salesDraftService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentToolDefinition definition() {
        Map<String, Object> itemSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "productId", Map.of("type", "integer", "description", "商品 ID"),
                        "quantity", Map.of("type", "integer", "minimum", 1, "description", "销售数量"),
                        "price", Map.of("type", "number", "minimum", 0, "description", "可选成交单价；省略时按历史价或客户类型价计算")),
                "required", List.of("productId", "quantity"));
        return new AgentToolDefinition(
                "create_sales_draft",
                "根据已确认的客户、商品、数量和可选价格创建销售草稿；此操作不会生成正式订单或扣减库存",
                AgentToolRisk.R1_DRAFT,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "customerId", Map.of("type", "integer", "description", "客户 ID"),
                                "receivedAmount", Map.of("type", "number", "minimum", 0, "description", "本次已收金额，默认 0"),
                                "items", Map.of("type", "array", "minItems", 1, "items", itemSchema)),
                        "required", List.of("customerId", "items")));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        SalesDraftRequest request = objectMapper.convertValue(arguments, SalesDraftRequest.class);
        return salesDraftService.createDraft(request, context.getOperatorId());
    }
}
