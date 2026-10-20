package com.example.demo.agent.harness;

import com.example.demo.agent.model.AgentRoutingResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentInstructionLoader {
    private static final Map<String, String> COMMON = Map.ofEntries(
            Map.entry("excel", "agent/skills/excel/SKILL.md"),
            Map.entry("inventory", "agent/skills/inventory/SKILL.md"),
            Map.entry("products", "agent/skills/products/SKILL.md"),
            Map.entry("sales", "agent/skills/sales/SKILL.md"),
            Map.entry("finance", "agent/skills/finance/SKILL.md"),
            Map.entry("suppliers", "agent/skills/suppliers/SKILL.md"),
            Map.entry("procurement", "agent/skills/procurement/SKILL.md"),
            Map.entry("factory_ledger", "agent/skills/factory-ledger/SKILL.md"),
            Map.entry("quotes", "agent/skills/quotes/SKILL.md"));
    private static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("excel.create", "agent/skills/excel/create/SKILL.md"),
            Map.entry("excel.modify_generated", "agent/skills/excel/modify-generated/SKILL.md"),
            Map.entry("excel.find", "agent/skills/excel/find/SKILL.md"),
            Map.entry("excel.file_tasks", "agent/skills/excel/file-tasks/SKILL.md"));

    public LoadedInstructions load(List<AgentRoutingResult.Selection> selections) {
        Map<String, String> versions = new LinkedHashMap<>();
        StringBuilder snapshot = new StringBuilder();
        for (AgentRoutingResult.Selection selection : selections) {
            String common = COMMON.get(selection.capabilityId());
            if (common != null) append(common, versions, snapshot);
            String operation = OPERATIONS.get(selection.capabilityId() + "." + selection.operation());
            if (operation != null) append(operation, versions, snapshot);
        }
        return new LoadedInstructions(snapshot.toString(), versions);
    }

    private void append(String path, Map<String, String> versions, StringBuilder snapshot) {
        if (versions.containsKey(path)) return;
        try {
            String content = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
            versions.put(path, sha256(content));
            snapshot.append("\n\n--- BEGIN ").append(path).append(" ---\n")
                    .append(content).append("\n--- END ").append(path).append(" ---");
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载 Agent 能力规范：" + path, exception);
        }
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    public record LoadedInstructions(String snapshot, Map<String, String> versions) {}
}
