package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.*;
import com.example.demo.finance.FinanceService;
import com.example.demo.finance.dto.PaymentRecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RecordAccountPaymentTool implements AgentTool {
    private final FinanceService service; private final ObjectMapper mapper;
    public RecordAccountPaymentTool(FinanceService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }
    public AgentToolDefinition definition() { return new AgentToolDefinition("record_account_payment", "为一张已确认的往来单据登记收款或付款", AgentToolRisk.R2_WRITE,
            Map.of("type", "object", "additionalProperties", false, "properties", Map.of(
                    "documentId", Map.of("type", "integer", "description", "往来单据 ID"), "amount", Map.of("type", "number", "exclusiveMinimum", 0),
                    "paymentDate", Map.of("type", "string", "description", "YYYY-MM-DD"), "paymentMethod", Map.of("type", "string"), "note", Map.of("type", "string")),
                    "required", List.of("documentId", "amount", "paymentDate")), "确认登记收付款"); }
    public Object approvalPreview(AgentToolContext context, Map<String, Object> args) { return args; }
    public Object execute(AgentToolContext context, Map<String, Object> args) { Object id = args.get("documentId"); if (!(id instanceof Number number)) throw new IllegalArgumentException("documentId 必须是整数"); Map<String, Object> body = new LinkedHashMap<>(args); body.remove("documentId"); return service.recordPayment(context.getOperatorId(), number.longValue(), mapper.convertValue(body, PaymentRecordRequest.class)); }
}
