package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.excel.service.ExcelTaskService;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ListFileTasksTool implements AgentTool {
    private final ExcelTaskService service;

    public ListFileTasksTool(ExcelTaskService service) { this.service = service; }

    @Override public AgentToolDefinition definition() {
        return new AgentToolDefinition("list_file_tasks",
                "查看当前用户最近的 Excel、CSV 或 PDF 文件任务及处理状态",
                AgentToolRisk.R0_READ_ONLY,
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));
    }

    @Override public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        return service.list(context.getOperatorId(), null).stream().limit(20).toList();
    }
}
