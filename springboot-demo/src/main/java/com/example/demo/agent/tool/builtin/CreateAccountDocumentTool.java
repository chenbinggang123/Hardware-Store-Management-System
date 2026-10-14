package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.*;
import com.example.demo.finance.FinanceService;
import com.example.demo.finance.dto.AccountDocumentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class CreateAccountDocumentTool implements AgentTool {
    private final FinanceService service; private final ObjectMapper mapper;
    public CreateAccountDocumentTool(FinanceService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }
    public AgentToolDefinition definition() { return new AgentToolDefinition("create_account_document", "建立一张客户应收或厂商应付单据，并计算剩余欠款", AgentToolRisk.R2_WRITE,
            Map.of("type", "object", "additionalProperties", false, "properties", Map.of(
                    "direction", Map.of("type", "string", "enum", List.of("RECEIVABLE", "PAYABLE")),
                    "counterpartyId", Map.of("type", "integer", "description", "已确认的客户或厂商 ID"),
                    "documentDate", Map.of("type", "string", "description", "YYYY-MM-DD"),
                    "documentNumber", Map.of("type", "string"), "totalAmount", Map.of("type", "number", "exclusiveMinimum", 0),
                    "settledAmount", Map.of("type", "number", "minimum", 0), "note", Map.of("type", "string")),
                    "required", List.of("direction", "counterpartyId", "documentDate", "totalAmount")), "确认建立往来账"); }
    public Object approvalPreview(AgentToolContext context, Map<String, Object> args) { return args; }
    public Object execute(AgentToolContext context, Map<String, Object> args) { return service.create(context.getOperatorId(), mapper.convertValue(args, AccountDocumentRequest.class)); }
}
