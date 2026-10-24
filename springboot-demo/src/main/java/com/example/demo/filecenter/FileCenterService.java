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
import com.example.demo.filecenter.dto.FileCenterActivityView;
import com.example.demo.filecenter.dto.FileCenterDetail;
import com.example.demo.filecenter.dto.FileCenterUpdateRequest;
import com.example.demo.filecenter.dto.FileAccountDraft;
import com.example.demo.filecenter.entity.FileCenterActivity;
import com.example.demo.filecenter.entity.FileCenterMetadata;
import com.example.demo.filecenter.repository.FileCenterActivityRepository;
import com.example.demo.filecenter.repository.FileCenterMetadataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional(readOnly = true)
public class FileCenterService {
    private static final Pattern ACCOUNT_AMOUNT = Pattern.compile("(?:合计|总计|价税合计|应收|应付|金额)[：:\\s]*[¥￥]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");
    private static final Pattern SETTLED_AMOUNT = Pattern.compile("(?:已付|已收|实付|实收)[：:\\s]*[¥￥]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");
    private static final Pattern DOCUMENT_DATE = Pattern.compile("(20\\d{2})[年/.-](\\d{1,2})[月/.-](\\d{1,2})日?");
    private static final Pattern DOCUMENT_NUMBER = Pattern.compile("(?:单号|票号|编号)[：:\\s]*([A-Za-z0-9_-]{3,40})");
    private static final Set<String> TASK_COMPLETED = Set.of("COMMITTED", "COMPLETED");
    private static final Set<String> TASK_FAILED = Set.of("FAILED", "CANCELLED", "PARTIAL");
    private static final Set<String> OUTPUT_FAILED = Set.of("FAILED", "MISSING");
    private static final Map<String, String> PURPOSES = Map.of(
            "CUSTOMER_RECEIPT", "客户单据",
            "SUPPLIER_RECEIPT", "厂商单据",
            "PRICE_LIST", "商品价格表",
            "BUYER_QUOTE", "客户询价",
            "SUPPLIER_PRICE", "供应商价格表",
            "PRODUCT_IMPORT", "商品资料",
            "INVENTORY_COUNT", "库存盘点",
            "PURCHASE_RECEIPT", "采购到货",
            "UNCLASSIFIED", "其他文件");
    private static final Set<String> SOURCE_KINDS = Set.of("ATTACHMENT", "TASK", "OUTPUT");
    private static final Set<String> RELATED_TYPES = Set.of("NONE", "CUSTOMER", "SUPPLIER");

    private final AgentAttachmentRepository attachmentRepository;
    private final ExcelTaskRepository taskRepository;
    private final ExcelTaskOutputRepository outputRepository;
    private final FileCenterMetadataRepository metadataRepository;
    private final FileCenterActivityRepository activityRepository;

    public FileCenterService(AgentAttachmentRepository attachmentRepository,
                             ExcelTaskRepository taskRepository,
                             ExcelTaskOutputRepository outputRepository,
                             FileCenterMetadataRepository metadataRepository,
                             FileCenterActivityRepository activityRepository) {
        this.attachmentRepository = attachmentRepository;
        this.taskRepository = taskRepository;
        this.outputRepository = outputRepository;
        this.metadataRepository = metadataRepository;
        this.activityRepository = activityRepository;
    }

    public FileCenterView list(Long operatorId, String state, String keyword) {
        if (operatorId == null) throw new IllegalArgumentException("缺少操作人");
        List<FileCenterItem> all = new ArrayList<>();
        Map<String, FileCenterMetadata> metadata = metadataRepository.findByOperatorId(operatorId).stream()
                .collect(Collectors.toMap(item -> key(item.getSourceKind(), item.getSourceId()), Function.identity()));
        attachmentRepository.findByOperatorIdOrderByUpdateTimeDesc(operatorId).forEach(item -> {
            FileCenterMetadata attachmentMetadata = metadata.get(key("ATTACHMENT", item.getId()));
            if (attachmentMetadata != null && Boolean.TRUE.equals(attachmentMetadata.getManualUpload())) {
                all.add(fromAttachment(item, attachmentMetadata));
            }
        });
        taskRepository.findByOperatorIdOrderByUpdateTimeDesc(operatorId)
                .forEach(item -> all.add(fromTask(item, metadata.get(key("TASK", item.getId())))));
        outputRepository.findByOperatorIdAndSourceTypeOrderByCreateTimeDesc(
                        operatorId, AgentOutputService.AGENT_SOURCE_TYPE)
                .forEach(item -> all.add(fromOutput(item, metadata.get(key("OUTPUT", item.getId())))));

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

    public FileCenterDetail detail(Long operatorId, String kind, Long id) {
        String sourceKind = normalizeKind(kind);
        FileCenterItem item = ownedItem(operatorId, sourceKind, id, null);
        FileCenterMetadata metadata = metadataRepository
                .findByOperatorIdAndSourceKindAndSourceId(operatorId, sourceKind, id).orElse(null);
        List<FileCenterActivityView> activities = new ArrayList<>();
        if (metadata != null) {
            activityRepository.findByMetadataIdOrderByCreateTimeDesc(metadata.getId()).stream()
                    .map(activity -> new FileCenterActivityView(activity.getActivityType(), activity.getTitle(),
                            activity.getDetail(), activity.getCreateTime()))
                    .forEach(activities::add);
        }
        activities.add(new FileCenterActivityView("CREATED", "文件进入系统", item.purpose(), item.createTime()));
        activities.sort(Comparator.comparing(FileCenterActivityView::createTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new FileCenterDetail(applyMetadata(item, metadata),
                metadata == null ? defaultPurposeCode(item) : metadata.getPurposeCode(),
                metadata == null ? "NONE" : metadata.getRelatedType(),
                metadata == null ? null : metadata.getRelatedId(),
                metadata == null ? null : metadata.getRelatedName(),
                metadata == null ? null : metadata.getNote(),
                metadata != null && Boolean.TRUE.equals(metadata.getManualUpload()), metadata != null, activities,
                accountDraft(operatorId, sourceKind, id));
    }

    @Transactional
    public FileCenterDetail update(Long operatorId, String kind, Long id, FileCenterUpdateRequest request) {
        if (request == null) throw new IllegalArgumentException("文件资料不能为空");
        String sourceKind = normalizeKind(kind);
        ownedItem(operatorId, sourceKind, id, null);
        String purposeCode = normalizePurpose(request.getPurposeCode());
        String relatedType = normalizeRelatedType(request.getRelatedType());
        if ("NONE".equals(relatedType)) {
            request.setRelatedId(null);
            request.setRelatedName(null);
        } else if (request.getRelatedId() == null || !StringUtils.hasText(request.getRelatedName())) {
            throw new IllegalArgumentException("请选择要关联的客户或厂商");
        }
        FileCenterMetadata metadata = metadataRepository
                .findByOperatorIdAndSourceKindAndSourceId(operatorId, sourceKind, id)
                .orElseGet(FileCenterMetadata::new);
        boolean created = metadata.getId() == null;
        LocalDateTime now = LocalDateTime.now();
        if (created) {
            metadata.setOperatorId(operatorId);
            metadata.setSourceKind(sourceKind);
            metadata.setSourceId(id);
            metadata.setCreateTime(now);
        }
        metadata.setPurposeCode(purposeCode);
        metadata.setRelatedType(relatedType);
        metadata.setRelatedId(request.getRelatedId());
        metadata.setRelatedName(clean(request.getRelatedName(), 255));
        metadata.setNote(clean(request.getNote(), 1000));
        metadata.setManualUpload(Boolean.TRUE.equals(request.getManualUpload()) || Boolean.TRUE.equals(metadata.getManualUpload()));
        metadata.setUpdateTime(now);
        metadata = metadataRepository.save(metadata);

        FileCenterActivity activity = new FileCenterActivity();
        activity.setMetadataId(metadata.getId());
        activity.setActivityType(created && Boolean.TRUE.equals(metadata.getManualUpload()) ? "UPLOADED" : "ORGANIZED");
        activity.setTitle(created && Boolean.TRUE.equals(metadata.getManualUpload()) ? "手动添加文件" : "更新文件资料");
        activity.setDetail(activityDetail(purposeCode, relatedType, metadata.getRelatedName()));
        activity.setCreateTime(now);
        activityRepository.save(activity);
        return detail(operatorId, sourceKind, id);
    }

    private FileCenterItem fromAttachment(AgentAttachment item, FileCenterMetadata metadata) {
        String state = "FAILED".equals(item.getParseStatus()) ? "FAILED"
                : item.getRunId() == null ? "PENDING" : "COMPLETED";
        return applyMetadata(new FileCenterItem("attachment-" + item.getId(), item.getId(), "ATTACHMENT",
                item.getOriginalName(), item.getMimeType(), value(item.getFileSize()), state,
                item.getParseStatus(), attachmentPurpose(item), item.getConversationId(), item.getRunId(),
                item.getCreateTime(), latest(item.getUpdateTime(), item.getCreateTime()), item.getErrorMessage(), false,
                "NONE", null, null, false, false), metadata);
    }

    private FileCenterItem fromTask(ExcelTask item, FileCenterMetadata metadata) {
        String state = TASK_COMPLETED.contains(item.getStatus()) ? "COMPLETED"
                : TASK_FAILED.contains(item.getStatus()) ? "FAILED" : "PENDING";
        return applyMetadata(new FileCenterItem("task-" + item.getId(), item.getId(), "TASK", item.getOriginalName(),
                item.getMimeType(), value(item.getFileSize()), state, item.getStatus(),
                item.getPurpose() == null ? "其他文件" : PURPOSES.getOrDefault(item.getPurpose(), "其他文件"), null, null,
                item.getCreateTime(), latest(item.getUpdateTime(), item.getCreateTime()), item.getErrorMessage(), false,
                "NONE", null, null, false, false), metadata);
    }

    private FileCenterItem fromOutput(ExcelTaskOutput item, FileCenterMetadata metadata) {
        String state = "READY".equals(item.getStatus()) ? "COMPLETED"
                : OUTPUT_FAILED.contains(item.getStatus()) ? "FAILED" : "PENDING";
        return applyMetadata(new FileCenterItem("output-" + item.getId(), item.getId(), "OUTPUT", item.getOriginalName(),
                item.getMimeType(), value(item.getFileSize()), state, item.getStatus(), "小五生成的文件",
                item.getConversationId(), item.getRunId(), item.getCreateTime(), item.getCreateTime(),
                item.getErrorMessage(), "READY".equals(item.getStatus()), "NONE", null, null, false, false), metadata);
    }

    private FileCenterItem ownedItem(Long operatorId, String kind, Long id, FileCenterMetadata metadata) {
        if (operatorId == null || id == null) throw new IllegalArgumentException("文件参数不完整");
        return switch (kind) {
            case "ATTACHMENT" -> fromAttachment(attachmentRepository.findByIdAndOperatorId(id, operatorId)
                    .orElseThrow(() -> new IllegalArgumentException("文件不存在或无权访问")), metadata);
            case "TASK" -> fromTask(taskRepository.findByIdAndOperatorId(id, operatorId)
                    .orElseThrow(() -> new IllegalArgumentException("文件不存在或无权访问")), metadata);
            case "OUTPUT" -> fromOutput(outputRepository.findByIdAndOperatorId(id, operatorId)
                    .orElseThrow(() -> new IllegalArgumentException("文件不存在或无权访问")), metadata);
            default -> throw new IllegalArgumentException("不支持的文件类型");
        };
    }

    private FileCenterItem applyMetadata(FileCenterItem item, FileCenterMetadata metadata) {
        if (metadata == null) return item;
        String state = Boolean.TRUE.equals(metadata.getManualUpload()) && "ATTACHMENT".equals(item.kind())
                && (safe(item.mimeType()).startsWith("image/") || !"FAILED".equals(item.state())) ? "COMPLETED" : item.state();
        return new FileCenterItem(item.key(), item.id(), item.kind(), item.name(), item.mimeType(), item.size(),
                state, item.sourceStatus(), metadata.getPurposeCode() == null ? "其他文件" : PURPOSES.getOrDefault(metadata.getPurposeCode(), "其他文件"),
                item.conversationId(), item.runId(), item.createTime(), latest(metadata.getUpdateTime(), item.updateTime()),
                item.errorMessage(), item.downloadable(), metadata.getRelatedType(), metadata.getRelatedId(),
                metadata.getRelatedName(), true, Boolean.TRUE.equals(metadata.getManualUpload()));
    }

    private String attachmentPurpose(AgentAttachment item) {
        String mime = item.getMimeType() == null ? "" : item.getMimeType().toLowerCase(Locale.ROOT);
        if (mime.startsWith("image/")) return "图片或单据";
        if (mime.contains("spreadsheet") || mime.contains("excel") || mime.contains("csv")) return "表格附件";
        if (mime.contains("pdf") || mime.contains("word")) return "文档附件";
        return "小五会话附件";
    }

    private String searchable(FileCenterItem item) {
        return (safe(item.name()) + " " + safe(item.purpose()) + " " + safe(item.relatedName()) + " " + safe(item.errorMessage()))
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

    private String normalizeKind(String value) {
        String result = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
        if (!SOURCE_KINDS.contains(result)) throw new IllegalArgumentException("不支持的文件类型");
        return result;
    }

    private String normalizePurpose(String value) {
        String result = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "UNCLASSIFIED";
        if (!PURPOSES.containsKey(result)) throw new IllegalArgumentException("不支持的文件用途");
        return result;
    }

    private String normalizeRelatedType(String value) {
        String result = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "NONE";
        if (!RELATED_TYPES.contains(result)) throw new IllegalArgumentException("不支持的关联对象类型");
        return result;
    }

    private String activityDetail(String purposeCode, String relatedType, String relatedName) {
        String purpose = PURPOSES.getOrDefault(purposeCode, "其他文件");
        return "NONE".equals(relatedType) ? "用途：" + purpose : "用途：" + purpose + "；关联：" + relatedName;
    }

    private String defaultPurposeCode(FileCenterItem item) {
        if ("客户询价".equals(item.purpose())) return "CUSTOMER_RECEIPT";
        if ("供应商价格表".equals(item.purpose()) || "表格附件".equals(item.purpose())) return "PRICE_LIST";
        if ("商品资料".equals(item.purpose())) return "PRODUCT_IMPORT";
        if ("库存盘点".equals(item.purpose())) return "INVENTORY_COUNT";
        if ("采购到货".equals(item.purpose())) return "SUPPLIER_RECEIPT";
        return "UNCLASSIFIED";
    }

    private FileAccountDraft accountDraft(Long operatorId, String kind, Long id) {
        if (!"ATTACHMENT".equals(kind)) return null;
        AgentAttachment attachment = attachmentRepository.findByIdAndOperatorId(id, operatorId).orElse(null);
        if (attachment == null || !StringUtils.hasText(attachment.getExtractedText())) return null;
        String text = attachment.getExtractedText();
        BigDecimal total = amount(text, ACCOUNT_AMOUNT);
        BigDecimal settled = amount(text, SETTLED_AMOUNT);
        LocalDate date = date(text);
        String number = match(text, DOCUMENT_NUMBER);
        List<String> review = new ArrayList<>();
        if (total == null) review.add("单据金额");
        if (date == null) review.add("单据日期");
        if (number == null) review.add("单号");
        if (settled == null) review.add("已收付金额");
        return new FileAccountDraft(number, date, total, settled, review);
    }

    private BigDecimal amount(String text, Pattern pattern) {
        String value = match(text, pattern);
        if (value == null) return null;
        try { return new BigDecimal(value.replace(",", "")); }
        catch (NumberFormatException ignored) { return null; }
    }

    private LocalDate date(String text) {
        Matcher matcher = DOCUMENT_DATE.matcher(text);
        if (!matcher.find()) return null;
        try { return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))); }
        catch (RuntimeException ignored) { return null; }
    }

    private String match(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String clean(String value, int maxLength) {
        if (!StringUtils.hasText(value)) return null;
        String result = value.trim();
        return result.length() <= maxLength ? result : result.substring(0, maxLength);
    }

    private String key(String kind, Long id) { return kind + "-" + id; }

    private long value(Long value) { return value == null ? 0 : value; }
    private String safe(String value) { return value == null ? "" : value; }
    private LocalDateTime latest(LocalDateTime preferred, LocalDateTime fallback) {
        return preferred == null ? fallback : preferred;
    }
}
