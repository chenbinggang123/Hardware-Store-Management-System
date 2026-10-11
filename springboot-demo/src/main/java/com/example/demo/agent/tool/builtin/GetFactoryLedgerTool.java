package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.service.FactoryLedgerService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GetFactoryLedgerTool implements AgentTool {
    private final FactoryLedgerService ledgerService;

    public GetFactoryLedgerTool(FactoryLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "get_factory_ledger",
                "根据唯一账本 ID 查看工厂账本、月度汇总和流水明细",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "ledgerId", Map.of("type", "integer", "description", "账本 ID"),
                                "month", Map.of("type", "string", "description", "月份，格式 YYYY-MM；留空为当前月份")),
                        "required", List.of("ledgerId")));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        return ledgerService.get(requiredLong(arguments, "ledgerId"), text(arguments, "month"));
    }

    private Long requiredLong(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " 必须是整数");
        return number.longValue();
    }

    private String text(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }
}
