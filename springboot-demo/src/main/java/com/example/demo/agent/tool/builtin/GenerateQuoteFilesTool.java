package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.excel.dto.ExcelQuoteRequest;
import com.example.demo.excel.service.ExcelQuoteService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GenerateQuoteFilesTool implements AgentTool {
    private final ExcelQuoteService service;

    public GenerateQuoteFilesTool(ExcelQuoteService service) { this.service = service; }

    @Override public AgentToolDefinition definition() {
        return new AgentToolDefinition("generate_quote_files",
                "为已经完成商品匹配的买家询价任务生成可编辑 Excel 和/或横向 PDF 报价文件；只生成文件，不修改价格、库存或订单",
                AgentToolRisk.R1_DRAFT,
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "taskId", Map.of("type", "integer", "description", "文件任务 ID"),
                                "expectedVersion", Map.of("type", "integer", "description", "文件任务当前版本"),
                                "pricingMode", Map.of("type", "string", "enum", List.of("RETAIL_PRICE", "WHOLESALE_PRICE", "OLD_CUSTOMER_PRICE", "CUSTOMER_TYPE_PRICE")),
                                "customerId", Map.of("type", List.of("integer", "null"), "description", "按客户类型报价时必填"),
                                "formats", Map.of("type", "array", "items", Map.of("type", "string", "enum", List.of("XLSX", "PDF")), "minItems", 1)
                        ),
                        "required", List.of("taskId", "expectedVersion", "pricingMode", "formats"),
                        "additionalProperties", false));
    }

    @Override public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Long taskId = number(arguments.get("taskId"));
        Long version = number(arguments.get("expectedVersion"));
        Long customerId = number(arguments.get("customerId"));
        String mode = String.valueOf(arguments.get("pricingMode"));
        Object formatsValue = arguments.get("formats");
        if (taskId == null || version == null || !(formatsValue instanceof List<?> formats) || formats.isEmpty()) {
            throw new IllegalArgumentException("taskId、expectedVersion、pricingMode 和 formats 不能为空");
        }
        List<Object> outputs = new ArrayList<>();
        long stamp = System.currentTimeMillis();
        for (Object value : formats) {
            String format = String.valueOf(value).toUpperCase(java.util.Locale.ROOT);
            outputs.add(service.generate(taskId, new ExcelQuoteRequest(version, mode, customerId, true,
                    "agent-quote-" + taskId + "-" + format.toLowerCase() + "-" + stamp, format), context.getOperatorId()));
        }
        return outputs;
    }

    private Long number(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }
}
