package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.service.FactoryLedgerService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class UpdateFactorySettlementStatusTool implements AgentTool {
    private static final Set<String> STATUSES = Set.of(
            "PENDING_SUMMARY", "PENDING_CHECK", "CONFIRMED", "INVOICED", "PARTIAL_PAID", "PAID", "CLOSED");

    private final FactoryLedgerService ledgerService;

    public UpdateFactorySettlementStatusTool(FactoryLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "update_factory_settlement_status",
                "更新一家工厂指定月份的月底结算进度",
                AgentToolRisk.R2_WRITE,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "ledgerId", Map.of("type", "integer", "description", "账本 ID"),
                                "month", Map.of("type", "string", "description", "月份，格式 YYYY-MM"),
                                "status", Map.of("type", "string", "enum", STATUSES.stream().sorted().toList(),
                                        "description", "待汇总、待核对、已确认、已开单、部分付款、已付款或已结清")),
                        "required", List.of("ledgerId", "month", "status")),
                "确认更新月底结算状态");
    }

    @Override
    public Object approvalPreview(AgentToolContext context, Map<String, Object> arguments) {
        Long ledgerId = requiredLong(arguments, "ledgerId");
        String month = requiredText(arguments, "month");
        String status = requiredText(arguments, "status");
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("无效的结账状态");
        Map<String, Object> detail = ledgerService.get(ledgerId, month);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("ledgerId", ledgerId);
        preview.put("ledgerNo", detail.get("ledgerNo"));
        preview.put("factoryName", detail.get("factoryName"));
        preview.put("month", month);
        preview.put("currentSummary", detail.get("summary"));
        preview.put("newStatus", status);
        preview.put("effect", "更新该工厂本月对账、开单或收款进度；不会新增收款流水");
        return preview;
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        return ledgerService.setSettlementStatus(
                requiredLong(arguments, "ledgerId"),
                requiredText(arguments, "month"),
                requiredText(arguments, "status"),
                context.getOperatorId());
    }

    private Long requiredLong(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " 必须是整数");
        return number.longValue();
    }

    private String requiredText(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.toString().trim();
    }
}
