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
public class SearchFactoryLedgersTool implements AgentTool {
    private final FactoryLedgerService ledgerService;

    public SearchFactoryLedgersTool(FactoryLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "search_factory_ledgers",
                "按工厂名称、联系人、电话或账本编号查询电子账本及指定月份应收",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "keyword", Map.of("type", "string", "description", "工厂、联系人、电话或账本编号；留空查询全部"),
                                "month", Map.of("type", "string", "description", "月份，格式 YYYY-MM；留空为当前月份")),
                        "required", List.of()));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        return ledgerService.list(text(arguments, "month"), text(arguments, "keyword"));
    }

    private String text(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }
}
