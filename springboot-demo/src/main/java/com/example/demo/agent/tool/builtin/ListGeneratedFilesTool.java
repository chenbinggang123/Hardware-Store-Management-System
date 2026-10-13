package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.dto.AgentFileView;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRisk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the model answer “生成的 Excel 在哪” from the database instead of from memory: it lists the
 * files the agent really generated for the current user, with their status and where to open them.
 */
@Component
public class ListGeneratedFilesTool implements AgentTool {
    private static final int MAX_FILES = 20;
    private final AgentOutputService outputService;

    public ListGeneratedFilesTool(AgentOutputService outputService) {
        this.outputService = outputService;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition("list_generated_files",
                "查询小五已经为当前用户生成的 Excel 文件及其状态；用户询问文件在哪、是否生成成功或想再次使用已生成文件时调用",
                AgentToolRisk.R0_READ_ONLY,
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));
    }

    @Override
    public Object execute(AgentToolContext context, Map<String, Object> arguments) {
        List<AgentFileView> files = outputService.listMine(context.getOperatorId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (AgentFileView file : files.stream().limit(MAX_FILES).toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("outputId", file.id());
            item.put("fileName", file.name());
            item.put("status", file.status());
            item.put("statusText", statusText(file.status()));
            item.put("createTime", file.createTime());
            item.put("fileSize", file.size());
            item.put("runId", file.runId());
            item.put("summary", file.summary());
            if (file.errorMessage() != null) item.put("errorMessage", file.errorMessage());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", files.size());
        result.put("files", items);
        result.put("whereToOpen", "文件显示在生成它的那条回复下方的文件卡中，也可以在对话页右上角“文件”或会话抽屉的“小五生成的文件”里打开、转发或删除");
        result.put("note", files.isEmpty()
                ? "当前用户还没有任何已生成的文件；如果之前回复过“已生成”，那次并没有真正生成，需要重新调用生成工具"
                : "只有状态为 READY 的文件可以打开；MISSING 表示文件内容已不在存储中，应提醒用户删除后重新生成");
        return result;
    }

    private String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "READY" -> "可打开";
            case "FAILED" -> "生成失败";
            case AgentOutputService.MISSING_STATUS -> "文件已丢失，需重新生成";
            default -> "生成中";
        };
    }
}
