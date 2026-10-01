package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.dto.SalesDraftPreview;
import com.example.demo.agent.dto.SalesDraftRequest;
import com.example.demo.agent.service.SalesDraftService;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class UpdateSalesDraftTool implements AgentTool {
    private final SalesDraftService salesDraftService;
    private final ObjectMapper objectMapper;

    public UpdateSalesDraftTool(SalesDraftService salesDraftService, ObjectMapper objectMapper) {
        this.salesDraftService = salesDraftService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentToolDefinition definition() {
        Map<String, Object> itemSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "productId", Map.of("type", "integer"),
                        "quantity", Map.of("type", "integer", "minimum", 1),
                        "price", Map.of("type", "number", "minimum", 0)),
                "required", List.of("productId", "quantity"));
        return new AgentToolDefinition(
                "update_sales_draft",
                "完整替换销售草稿的客户、商品、数量、价格和已收金额，不提交正式订单",
                AgentToolRisk.R1_DRAFT,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "draftId", Map.of("type", "integer"),
                                "draftVersion", Map.of("type", "integer"),
                                "customerId", Map.of("type", "integer"),
                                "receivedAmount", Map.of("type", "number", "minimum", 0),
                                "items", Map.of("type", "array", "minItems", 1, "items", itemSchema)),
                        "required", List.of("draftId", "draftVersion", "customerId", "items")),
                "确认修改销售草稿");
    }

    @Override
    public Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
        Long draftId = requiredLong(arguments, "draftId");
        SalesDraftPreview preview = salesDraftService.previewUpdateDraft(
                draftId, toRequest(arguments), context.getOperatorId());
        return Map.of(
                "draftId", preview.getDraftId(),
                "draftVersion", preview.getVersion(),
                "customerName", preview.getCustomerName(),
                "totalAmount", preview.getTotalAmount(),
                "receivedAmount", preview.getReceivedAmount(),
                "newDebtAmount", preview.getNewDebtAmount(),
                "debtAfterCommit", preview.getDebtAfterCommit(),
                "inventorySufficient", preview.isInventorySufficient(),
                "items", preview.getItems(),
                "effect", "仅更新销售草稿，不创建正式订单、不修改欠款、不扣减库存");
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        return salesDraftService.updateDraft(
                requiredLong(arguments, "draftId"), toRequest(arguments), context.getOperatorId());
    }

    private SalesDraftRequest toRequest(Map<String, Object> arguments) {
        Map<String, Object> requestArguments = new LinkedHashMap<>(arguments);
        requestArguments.remove("draftId");
        SalesDraftRequest request = objectMapper.convertValue(requestArguments, SalesDraftRequest.class);
        request.setDraftVersion(requiredLong(arguments, "draftVersion"));
        return request;
    }

    private Long requiredLong(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " 必须是整数");
        }
        return number.longValue();
    }
}
