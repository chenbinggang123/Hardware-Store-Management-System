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
public class GetFactorySettlementSummaryTool implements AgentTool {
    private final FactoryLedgerService ledgerService;

    public GetFactorySettlementSummaryTool(FactoryLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "get_factory_settlement_summary",
                "查询指定月份全部工厂的拿货、退货、收款、应收和月底结算进度",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "month", Map.of("type", "string", "description", "月份，格式 YYYY-MM；留空为当前月份")),
                        "required", List.of()));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("month");
        String month = value == null || value.toString().isBlank() ? null : value.toString().trim();
        return ledgerService.list(month, null);
    }
}
