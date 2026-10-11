package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.dto.FactoryLedgerTransactionRequest;
import com.example.demo.service.FactoryLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RecordFactoryLedgerTransactionTool implements AgentTool {
    private final FactoryLedgerService ledgerService;
    private final ObjectMapper objectMapper;

    public RecordFactoryLedgerTransactionTool(FactoryLedgerService ledgerService, ObjectMapper objectMapper) {
        this.ledgerService = ledgerService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentToolDefinition definition() {
        Map<String, Object> itemSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "productName", Map.of("type", "string", "description", "商品名称"),
                        "specification", Map.of("type", "string", "description", "规格型号"),
                        "unit", Map.of("type", "string", "description", "单位"),
                        "quantity", Map.of("type", "number", "exclusiveMinimum", 0, "description", "数量"),
                        "unitPrice", Map.of("type", "number", "minimum", 0, "description", "单价，单位元")),
                "required", List.of("productName", "quantity", "unitPrice"));
        return new AgentToolDefinition(
                "record_factory_ledger_transaction",
                "向唯一工厂账本记录拿货、退货或收款，并自动计算记账前后应收",
                AgentToolRisk.R2_WRITE,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "ledgerId", Map.of("type", "integer", "description", "通过账本查询确认的唯一账本 ID"),
                                "type", Map.of("type", "string", "enum", List.of("PURCHASE", "RETURN", "PAYMENT"), "description", "拿货、退货或收款"),
                                "date", Map.of("type", "string", "description", "业务日期，格式 YYYY-MM-DD"),
                                "receiptNo", Map.of("type", "string", "description", "收据或单据编号"),
                                "paymentAmount", Map.of("type", "number", "exclusiveMinimum", 0, "description", "收款金额；PAYMENT 时填写，单位元"),
                                "remark", Map.of("type", "string", "description", "备注"),
                                "items", Map.of("type", "array", "items", itemSchema, "description", "拿货或退货商品明细")),
                        "required", List.of("ledgerId", "type", "date")),
                "确认写入工厂账本");
    }

    @Override
    public Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
        return ledgerService.previewTransaction(requiredLong(arguments, "ledgerId"), request(arguments));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        return ledgerService.addAgentTransaction(
                requiredLong(arguments, "ledgerId"), request(arguments), context.getOperatorId());
    }

    private FactoryLedgerTransactionRequest request(Map<String, Object> arguments) {
        Map<String, Object> requestArguments = new LinkedHashMap<>(arguments);
        requestArguments.remove("ledgerId");
        return objectMapper.convertValue(requestArguments, FactoryLedgerTransactionRequest.class);
    }

    private Long requiredLong(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " 必须是整数");
        return number.longValue();
    }
}
