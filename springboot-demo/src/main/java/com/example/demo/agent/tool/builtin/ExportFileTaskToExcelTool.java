package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.excel.dto.ExcelWorkbookExportRequest;
import com.example.demo.excel.service.ExcelWorkbookExportService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ExportFileTaskToExcelTool implements AgentTool {
    private final ExcelWorkbookExportService service;

    public ExportFileTaskToExcelTool(ExcelWorkbookExportService service) { this.service = service; }

    @Override public AgentToolDefinition definition() {
        return new AgentToolDefinition("export_file_task_to_excel",
                "把已读取的 Word、PDF、CSV 或 Excel 文件任务转换为可编辑 XLSX；保留逻辑表格、表头和数据行，不修改库存、价格或订单",
                AgentToolRisk.R1_DRAFT,
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "taskId", Map.of("type", "integer", "description", "文件任务 ID"),
                                "expectedVersion", Map.of("type", "integer", "description", "文件任务当前版本")
                        ),
                        "required", List.of("taskId", "expectedVersion"),
                        "additionalProperties", false));
    }

    @Override public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Long taskId = number(arguments.get("taskId"));
        Long version = number(arguments.get("expectedVersion"));
        if (taskId == null || version == null) throw new IllegalArgumentException("taskId 和 expectedVersion 不能为空");
        return service.generate(taskId, new ExcelWorkbookExportRequest(version,
                "agent-xlsx-" + taskId + "-" + version + "-" + context.getRunId()), context.getOperatorId());
    }

    private Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }
}
