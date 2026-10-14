package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.*;
import com.example.demo.finance.FinanceService;
import org.springframework.stereotype.Component;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

@Component
public class QueryAccountsTool implements AgentTool {
    private final FinanceService service;
    public QueryAccountsTool(FinanceService service) { this.service = service; }
    public AgentToolDefinition definition() { return new AgentToolDefinition("query_accounts", "查询客户应收、厂商应付和未结单据", AgentToolRisk.R0_READ_ONLY,
            Map.of("type", "object", "additionalProperties", false, "properties", Map.of(
                    "direction", Map.of("type", "string", "enum", List.of("RECEIVABLE", "PAYABLE"), "description", "应收或应付"),
                    "status", Map.of("type", "string", "enum", List.of("OPEN", "SETTLED"), "description", "未结或已结"),
                    "keyword", Map.of("type", "string", "description", "客户、厂商、单号或备注关键词"))), null); }
    public Object execute(AgentToolContext context, Map<String, Object> args) { return service.overview(context.getOperatorId(), text(args, "direction"), text(args, "status"), text(args, "keyword"), YearMonth.now()); }
    private String text(Map<String, Object> args, String key) { Object value = args == null ? null : args.get(key); return value == null ? null : value.toString(); }
}
