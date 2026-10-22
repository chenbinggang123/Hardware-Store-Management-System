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
                "查询指定月份工厂账本：kind=CUSTOMER（默认，买的：拿货、退货、收款、应收）或 SUPPLIER（进货的：进货、付款、我们还欠）及月底结算进度",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "month", Map.of("type", "string", "description", "月份，格式 YYYY-MM；留空为当前月份"),
                                "kind", Map.of("type", "string", "enum", List.of("CUSTOMER", "SUPPLIER"), "description", "CUSTOMER=买的（应收），SUPPLIER=进货的（应付）")),
                        "required", List.of()));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("month");
        String month = value == null || value.toString().isBlank() ? null : value.toString().trim();
        Object kind = arguments == null ? null : arguments.get("kind");
        // 应收和应付不能混在一起合计，默认只看“买的”。
        return ledgerService.list(month, null, kind == null || kind.toString().isBlank() ? FactoryLedgerService.KIND_CUSTOMER : kind.toString());
    }
}
