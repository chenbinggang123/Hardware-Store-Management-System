package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.dto.FactoryLedgerCreateRequest;
import com.example.demo.service.FactoryLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CreateFactoryLedgerTool implements AgentTool {
    private final FactoryLedgerService ledgerService;
    private final ObjectMapper objectMapper;

    public CreateFactoryLedgerTool(FactoryLedgerService ledgerService, ObjectMapper objectMapper) {
        this.ledgerService = ledgerService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "create_factory_ledger",
                "为一家月结工厂新建电子账本；建账后可记录拿货、退货和收款",
                AgentToolRisk.R2_WRITE,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "factoryName", Map.of("type", "string", "description", "工厂名称"),
                                "contactPerson", Map.of("type", "string", "description", "联系人"),
                                "phone", Map.of("type", "string", "description", "联系电话"),
                                "startDate", Map.of("type", "string", "description", "启用日期，格式 YYYY-MM-DD"),
                                "settlementDay", Map.of("type", "integer", "minimum", 1, "maximum", 31, "description", "每月结账日"),
                                "openingBalance", Map.of("type", "number", "minimum", 0, "description", "建账前未结金额，单位元"),
                                "remark", Map.of("type", "string", "description", "备注")),
                        "required", List.of("factoryName")),
                "确认新建工厂账本");
    }

    @Override
    public Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
        FactoryLedgerCreateRequest request = request(arguments);
        if (request.getFactoryName() == null || request.getFactoryName().isBlank()) {
            throw new IllegalArgumentException("工厂名称不能为空");
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("factoryName", request.getFactoryName().trim());
        preview.put("contactPerson", request.getContactPerson());
        preview.put("phone", request.getPhone());
        preview.put("startDate", request.getStartDate());
        preview.put("settlementDay", request.getSettlementDay() == null ? 31 : request.getSettlementDay());
        preview.put("openingBalance", request.getOpeningBalance() == null ? 0 : request.getOpeningBalance());
        preview.put("remark", request.getRemark());
        preview.put("effect", "新建工厂电子账本；后续拿货、退货和收款都记入此账本");
        return preview;
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        return ledgerService.create(request(arguments), context.getOperatorId());
    }

    private FactoryLedgerCreateRequest request(Map<String, Object> arguments) {
        return objectMapper.convertValue(arguments, FactoryLedgerCreateRequest.class);
    }
}
