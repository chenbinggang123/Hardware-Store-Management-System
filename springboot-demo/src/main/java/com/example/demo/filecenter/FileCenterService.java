package com.example.demo.filecenter;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.filecenter.dto.FileCenterItem;
import com.example.demo.filecenter.dto.FileCenterView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class FileCenterService {
    private static final Set<String> TASK_COMPLETED = Set.of("COMMITTED", "COMPLETED");
    private static final Set<String> TASK_FAILED = Set.of("FAILED", "CANCELLED", "PARTIAL");
    private static final Set<String> OUTPUT_FAILED = Set.of("FAILED", "MISSING");
    private static final Map<String, String> PURPOSES = Map.of(
            "BUYER_QUOTE", "客户询价",
            "SUPPLIER_PRICE", "供应商价格表",
            "PRODUCT_IMPORT", "商品资料",
            "INVENTORY_COUNT", "库存盘点",
            "PURCHASE_RECEIPT", "采购到货",
            "UNCLASSIFIED", "其他文件");

    private final AgentAttachmentRepository attachmentRepository;
    private final ExcelTaskRepository taskRepository;
    private final ExcelTaskOutputRepository outputRepository;

    public FileCenterService(AgentAttachmentRepository attachmentRepository,
                             ExcelTaskRepository taskRepository,
                             ExcelTaskOutputRepository outputRepository) {
        this.attachmentRepository = attachmentRepository;
        this.taskRepository = taskRepository;
        this.outputRepository = outputRepository;
    }

    public FileCenterView list(Long operatorId, String state, String keyword) {
        if (operatorId == null) throw new IllegalArgumentException("缺少操作人");
        List<FileCenterItem> all = new ArrayList<>();
        attachmentRepository.findByOperatorIdOrderByUpdateTimeDesc(operatorId)
                .forEach(item -> all.add(fromAttachment(item)));
        taskRepository.findByOperatorIdOrderByUpdateTimeDesc(operatorId)
                .forEach(item -> all.add(fromTask(item)));
        outputRepository.findByOperatorIdAndSourceTypeOrderByCreateTimeDesc(
                        operatorId, AgentOutputService.AGENT_SOURCE_TYPE)
                .forEach(item -> all.add(fromOutput(item)));

        all.sort(Comparator.comparing(FileCenterItem::updateTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        String wantedState = normalizeState(state);
        String wantedKeyword = normalizeKeyword(keyword);
        List<FileCenterItem> visible = all.stream()
                .filter(item -> wantedState == null || wantedState.equals(item.state()))
                .filter(item -> wantedKeyword == null || searchable(item).contains(wantedKeyword))
                .toList();
        return new FileCenterView(visible, all.size(), count(all, "PENDING"),
                count(all, "COMPLETED"), count(all, "FAILED"));
    }

    private FileCenterItem fromAttachment(AgentAttachment item) {
        String state = "FAILED".equals(item.getParseStatus()) ? "FAILED"
                : item.getRunId() == null ? "PENDING" : "COMPLETED";
        return new FileCenterItem("attachment-" + item.getId(), item.getId(), "ATTACHMENT",
                item.getOriginalName(), item.getMimeType(), value(item.getFileSize()), state,
                item.getParseStatus(), attachmentPurpose(item), item.getConversationId(), item.getRunId(),
                item.getCreateTime(), latest(item.getUpdateTime(), item.getCreateTime()), item.getErrorMessage(), false);
    }

    private FileCenterItem fromTask(ExcelTask item) {
        String state = TASK_COMPLETED.contains(item.getStatus()) ? "COMPLETED"
                : TASK_FAILED.contains(item.getStatus()) ? "FAILED" : "PENDING";
        return new FileCenterItem("task-" + item.getId(), item.getId(), "TASK", item.getOriginalName(),
                item.getMimeType(), value(item.getFileSize()), state, item.getStatus(),
                item.getPurpose() == null ? "其他文件" : PURPOSES.getOrDefault(item.getPurpose(), "其他文件"), null, null,
                item.getCreateTime(), latest(item.getUpdateTime(), item.getCreateTime()), item.getErrorMessage(), false);
    }

    private FileCenterItem fromOutput(ExcelTaskOutput item) {
        String state = "READY".equals(item.getStatus()) ? "COMPLETED"
                : OUTPUT_FAILED.contains(item.getStatus()) ? "FAILED" : "PENDING";
        return new FileCenterItem("output-" + item.getId(), item.getId(), "OUTPUT", item.getOriginalName(),
                item.getMimeType(), value(item.getFileSize()), state, item.getStatus(), "小五生成的文件",
                item.getConversationId(), item.getRunId(), item.getCreateTime(), item.getCreateTime(),
                item.getErrorMessage(), "READY".equals(item.getStatus()));
    }

    private String attachmentPurpose(AgentAttachment item) {
        String mime = item.getMimeType() == null ? "" : item.getMimeType().toLowerCase(Locale.ROOT);
        if (mime.startsWith("image/")) return "图片或单据";
        if (mime.contains("spreadsheet") || mime.contains("excel") || mime.contains("csv")) return "表格附件";
        if (mime.contains("pdf") || mime.contains("word")) return "文档附件";
        return "小五会话附件";
    }

    private String searchable(FileCenterItem item) {
        return (safe(item.name()) + " " + safe(item.purpose()) + " " + safe(item.errorMessage()))
                .toLowerCase(Locale.ROOT);
    }

    private int count(List<FileCenterItem> items, String state) {
        return (int) items.stream().filter(item -> state.equals(item.state())).count();
    }

    private String normalizeKeyword(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
    }

    private String normalizeState(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private long value(Long value) { return value == null ? 0 : value; }
    private String safe(String value) { return value == null ? "" : value; }
    private LocalDateTime latest(LocalDateTime preferred, LocalDateTime fallback) {
        return preferred == null ? fallback : preferred;
    }
}
