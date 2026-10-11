package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.dto.SourcePurchaseOrderRequest;
import com.example.demo.agent.service.SourcePurchaseOrderService;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class RecordSourcePurchaseOrderTool implements AgentTool {
    private final SourcePurchaseOrderService sourcePurchaseOrderService;
    private final ObjectMapper objectMapper;

    public RecordSourcePurchaseOrderTool(
            SourcePurchaseOrderService sourcePurchaseOrderService,
            ObjectMapper objectMapper) {
        this.sourcePurchaseOrderService = sourcePurchaseOrderService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentToolDefinition definition() {
        Map<String, Object> itemProperties = Map.ofEntries(
                Map.entry("lineNo", Map.of("type", "integer", "minimum", 1, "description", "单据行号")),
                Map.entry("barcode", Map.of("type", "string", "description", "条码或厂商 SKU")),
                Map.entry("productName", Map.of("type", "string", "description", "商品名称")),
                Map.entry("specification", Map.of("type", "string", "description", "规格型号")),
                Map.entry("unit", Map.of("type", "string", "description", "单位")),
                Map.entry("quantity", Map.of("type", "number", "exclusiveMinimum", 0, "description", "数量")),
                Map.entry("listUnitPrice", Map.of("type", "number", "minimum", 0, "description", "折前单价")),
                Map.entry("discount", Map.of("type", "number", "exclusiveMinimum", 0, "maximum", 1, "description", "折扣小数，如 0.57")),
                Map.entry("netUnitPrice", Map.of("type", "number", "minimum", 0, "description", "折后单价")),
                Map.entry("lineAmount", Map.of("type", "number", "minimum", 0, "description", "折后行金额")),
                Map.entry("remark", Map.of("type", "string", "description", "行备注")));
        Map<String, Object> itemSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", itemProperties,
                "required", List.of("productName", "quantity"));
        return new AgentToolDefinition(
                "record_source_purchase_order",
                "把当前消息中的源头厂商订单附件保存为采购凭证；可在明确要求时同步创建采购单和入库",
                AgentToolRisk.R2_WRITE,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "attachmentId", Map.of("type", "integer", "description", "当前消息中的附件 ID；只有一个附件时可省略"),
                                "supplierName", Map.of("type", "string", "description", "供货厂商全称"),
                                "sourceOrderNo", Map.of("type", "string", "description", "厂商原始单号"),
                                "orderDate", Map.of("type", "string", "description", "订单日期，格式 YYYY-MM-DD"),
                                "buyerName", Map.of("type", "string", "description", "购货单位"),
                                "handlerName", Map.of("type", "string", "description", "经手人或制单人"),
                                "declaredTotalAmount", Map.of("type", "number", "minimum", 0, "description", "单据印刷合计金额"),
                                "receiveStock", Map.of("type", "boolean", "description", "只有用户明确要求入库时才为 true"),
                                "remark", Map.of("type", "string", "description", "订单备注或售后条款摘要"),
                                "items", Map.of("type", "array", "minItems", 1, "items", itemSchema)),
                        "required", List.of("supplierName", "receiveStock", "items")),
                "确认保存源头厂商订单");
    }

    @Override
    public Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
        return sourcePurchaseOrderService.preview(
                context.getRunId(), context.getOperatorId(), request(arguments));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        return sourcePurchaseOrderService.save(
                context.getRunId(), context.getOperatorId(), request(arguments));
    }

    private SourcePurchaseOrderRequest request(Map<String, Object> arguments) {
        return objectMapper.convertValue(arguments, SourcePurchaseOrderRequest.class);
    }
}
