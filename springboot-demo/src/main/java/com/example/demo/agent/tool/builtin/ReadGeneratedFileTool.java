package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Gives the model the rows of a file it generated earlier, so “把刚才的表格加一列备注” can be
 * answered by reading, editing and regenerating instead of guessing from conversation history.
 */
@Component
public class ReadGeneratedFileTool implements AgentTool {
    private final AgentOutputService outputService;

    public ReadGeneratedFileTool(AgentOutputService outputService) {
        this.outputService = outputService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition("read_generated_file",
                "读取小五之前生成的某个 Excel 文件的表头和数据行；用户要修改、补充或基于已生成文件再做一份时，先调用它再调用 generate_excel_workbook",
                AgentToolRisk.R0_READ_ONLY,
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "outputId", Map.of("type", "integer", "description", "文件 ID，来自文件卡、list_generated_files 或用户消息中的“文件 #ID”"),
                                "sheetName", Map.of("type", List.of("string", "null"), "description", "只读某个工作表时填写，默认读取全部数据工作表"),
                                "maxRows", Map.of("type", List.of("integer", "null"), "description", "每个工作表最多返回的行数，默认 200，最多 500")),
                        "required", List.of("outputId")));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        Long outputId = number(arguments == null ? null : arguments.get("outputId"));
        if (outputId == null) throw new IllegalArgumentException("outputId 不能为空");
        Object sheet = arguments.get("sheetName");
        Long maxRows = number(arguments.get("maxRows"));
        return outputService.readWorkbookContent(outputId, context.getOperatorId(),
                sheet == null ? null : String.valueOf(sheet), maxRows == null ? null : maxRows.intValue());
    }

    private Long number(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
