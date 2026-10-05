package com.example.demo.excel.service;

import com.example.demo.excel.dto.ExcelComparisonItemPage;
import com.example.demo.excel.dto.ExcelComparisonItemView;
import com.example.demo.excel.dto.ExcelComparisonRequest;
import com.example.demo.excel.dto.ExcelComparisonView;
import com.example.demo.excel.entity.ExcelComparison;
import com.example.demo.excel.entity.ExcelComparisonItem;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskRowResult;
import com.example.demo.excel.repository.ExcelComparisonItemRepository;
import com.example.demo.excel.repository.ExcelComparisonRepository;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.excel.repository.ExcelTaskRowResultRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

@Service
public class ExcelComparisonService {
    private static final Set<String> MATCH_KEYS = Set.of("BARCODE", "SPEC", "SUPPLIER_SKU");
    private static final Set<String> FIELDS = Set.of(
            "PRODUCT_NAME", "BARCODE", "SUPPLIER_SKU", "SPEC", "UNIT", "UNIT_PRICE", "QUANTITY");

    private final ExcelTaskRepository taskRepository;
    private final ExcelTaskRowResultRepository rowRepository;
    private final ExcelComparisonRepository comparisonRepository;
    private final ExcelComparisonItemRepository itemRepository;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public ExcelComparisonService(ExcelTaskRepository taskRepository,
                                  ExcelTaskRowResultRepository rowRepository,
                                  ExcelComparisonRepository comparisonRepository,
                                  ExcelComparisonItemRepository itemRepository,
                                  ObjectMapper objectMapper,
                                  @Qualifier("agentTaskExecutor") Executor executor) {
        this.taskRepository = taskRepository;
        this.rowRepository = rowRepository;
        this.comparisonRepository = comparisonRepository;
        this.itemRepository = itemRepository;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Transactional
    public ExcelComparisonView create(ExcelComparisonRequest request, Long operatorId) {
        if (request == null || request.baseTaskId() == null || request.newTaskId() == null) {
            throw new IllegalArgumentException("比较任务参数不完整");
        }
        if (request.baseTaskId().equals(request.newTaskId())) {
            throw new IllegalArgumentException("基准任务和新任务不能相同");
        }
        ExcelTask base = requireTask(request.baseTaskId(), operatorId);
        ExcelTask newer = requireTask(request.newTaskId(), operatorId);
        requireVersion(base, request.baseTaskVersion());
        requireVersion(newer, request.newTaskVersion());
        if (!base.getPurpose().equals(newer.getPurpose())) throw new IllegalArgumentException("两个文件任务用途不一致");
        List<String> keys = normalize(request.matchKeys(), MATCH_KEYS, List.of("BARCODE"));
        List<String> fields = normalize(request.compareFields(), FIELDS,
                List.of("PRODUCT_NAME", "SPEC", "UNIT", "UNIT_PRICE", "QUANTITY"));
        if (keys.contains("SUPPLIER_SKU") && !"SUPPLIER_PRICE".equals(base.getPurpose())) {
            throw new IllegalArgumentException("只有供应商价格任务可以按厂家货号比较");
        }
        if (keys.contains("SUPPLIER_SKU") && (base.getSupplierId() == null
                || !base.getSupplierId().equals(newer.getSupplierId()))) {
            throw new IllegalArgumentException("按厂家货号比较时，两个任务必须属于同一供应商");
        }
        String idempotencyKey = normalizeKey(request.idempotencyKey());
        String digest = sha256(base.getId() + "|" + base.getVersion() + "|" + newer.getId() + "|"
                + newer.getVersion() + "|" + keys + "|" + fields);
        var prior = comparisonRepository.findByOperatorIdAndIdempotencyKey(operatorId, idempotencyKey);
        if (prior.isPresent()) {
            if (!digest.equals(prior.get().getRequestDigest())) {
                throw new IllegalArgumentException("比较幂等键已用于不同请求");
            }
            return view(prior.get());
        }
        LocalDateTime now = LocalDateTime.now();
        ExcelComparison comparison = new ExcelComparison();
        comparison.setOperatorId(operatorId);
        comparison.setBaseTaskId(base.getId());
        comparison.setBaseTaskVersion(base.getVersion());
        comparison.setNewTaskId(newer.getId());
        comparison.setNewTaskVersion(newer.getVersion());
        comparison.setStatus("QUEUED");
        comparison.setMatchKeysJson(write(keys));
        comparison.setCompareFieldsJson(write(fields));
        comparison.setRequestDigest(digest);
        comparison.setIdempotencyKey(idempotencyKey);
        comparison.setSummaryJson("{}");
        comparison.setProgressCompleted(0);
        comparison.setProgressTotal(0);
        comparison.setIssueCount(0);
        comparison.setCreateTime(now);
        comparison.setUpdateTime(now);
        comparison = comparisonRepository.saveAndFlush(comparison);
        Long id = comparison.getId();
        Runnable work = () -> compare(id);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { executor.execute(work); }
            });
        } else executor.execute(work);
        return view(comparison);
    }

    private void compare(Long id) {
        ExcelComparison comparison = comparisonRepository.findById(id).orElse(null);
        if (comparison == null || "CANCELLED".equals(comparison.getStatus())) return;
        try {
            comparison.setStatus("RUNNING");
            comparison.setUpdateTime(LocalDateTime.now());
            comparisonRepository.save(comparison);
            List<String> keys = readList(comparison.getMatchKeysJson());
            List<String> fields = readList(comparison.getCompareFieldsJson());
            List<ExcelTaskRowResult> baseRows = usableRows(comparison.getBaseTaskId());
            List<ExcelTaskRowResult> newRows = usableRows(comparison.getNewTaskId());
            comparison.setProgressTotal(baseRows.size() + newRows.size());
            comparisonRepository.save(comparison);
            Map<String, List<RowSnapshot>> base = index(baseRows, keys, "BASE");
            Map<String, List<RowSnapshot>> newer = index(newRows, keys, "NEW");
            Set<String> allKeys = new LinkedHashSet<>();
            allKeys.addAll(base.keySet());
            allKeys.addAll(newer.keySet());
            List<ExcelComparisonItem> items = new ArrayList<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String key : allKeys) {
                ExcelComparison current = comparisonRepository.findById(id).orElseThrow();
                if ("CANCELLED".equals(current.getStatus())) return;
                List<RowSnapshot> left = base.getOrDefault(key, List.of());
                List<RowSnapshot> right = newer.getOrDefault(key, List.of());
                ExcelComparisonItem item;
                if (key.startsWith("__issue:") || left.size() > 1 || right.size() > 1) {
                    item = issue(id, key, left, right, "DUPLICATE_OR_MISSING_KEY", "匹配键缺失或重复");
                } else if (left.isEmpty()) item = added(id, key, right.get(0));
                else if (right.isEmpty()) item = removed(id, key, left.get(0));
                else item = changed(id, key, left.get(0), right.get(0), fields);
                counts.merge(item.getChangeType(), 1, Integer::sum);
                items.add(item);
            }
            itemRepository.saveAll(items);
            comparison = comparisonRepository.findById(id).orElseThrow();
            if ("CANCELLED".equals(comparison.getStatus())) return;
            int issueCount = counts.getOrDefault("ISSUE", 0);
            comparison.setIssueCount(issueCount);
            comparison.setProgressCompleted(comparison.getProgressTotal());
            comparison.setSummaryJson(write(Map.of(
                    "added", counts.getOrDefault("ADDED", 0),
                    "removed", counts.getOrDefault("REMOVED", 0),
                    "changed", counts.getOrDefault("CHANGED", 0),
                    "unchanged", counts.getOrDefault("UNCHANGED", 0),
                    "issues", issueCount)));
            comparison.setStatus(issueCount > 0 ? "PARTIAL" : "COMPLETED");
            comparison.setUpdateTime(LocalDateTime.now());
            comparisonRepository.save(comparison);
        } catch (RuntimeException exception) {
            comparison = comparisonRepository.findById(id).orElse(comparison);
            if (!"CANCELLED".equals(comparison.getStatus())) {
                comparison.setStatus("FAILED");
                comparison.setErrorCode(exception.getClass().getSimpleName());
                comparison.setUpdateTime(LocalDateTime.now());
                comparisonRepository.save(comparison);
            }
        }
    }

    private List<ExcelTaskRowResult> usableRows(Long taskId) {
        return rowRepository.findByTaskIdOrderBySheetIdAscRowNumberAsc(taskId).stream()
                .filter(row -> !"EXCLUDED".equals(row.getMatchStatus()))
                .toList();
    }

    private Map<String, List<RowSnapshot>> index(List<ExcelTaskRowResult> rows, List<String> keys, String side) {
        Map<String, List<RowSnapshot>> result = new LinkedHashMap<>();
        for (ExcelTaskRowResult row : rows) {
            Map<String, Object> values = readMap(row.getNormalizedJson());
            String key = key(values, keys);
            if (!StringUtils.hasText(key)) key = "__issue:" + side + ":" + row.getId();
            RowSnapshot snapshot = new RowSnapshot(row, values);
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(snapshot);
        }
        return result;
    }

    private String key(Map<String, Object> values, List<String> keys) {
        List<String> parts = new ArrayList<>();
        for (String field : keys) {
            String value = normalizeText(values.get(field));
            if (!StringUtils.hasText(value)) return null;
            parts.add(value);
        }
        return String.join("|", parts);
    }

    private ExcelComparisonItem changed(Long comparisonId, String key, RowSnapshot left,
                                        RowSnapshot right, List<String> fields) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String field : fields) {
            Object before = left.values().get(field);
            Object after = right.values().get(field);
            if (!same(field, before, after)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("before", before);
                change.put("after", after);
                change.put("changePercent", percent(field, before, after));
                changes.put(field, change);
            }
        }
        ExcelComparisonItem item = baseItem(comparisonId, changes.isEmpty() ? "UNCHANGED" : "CHANGED", key,
                left, right);
        item.setChangesJson(write(changes));
        return item;
    }

    private ExcelComparisonItem added(Long comparisonId, String key, RowSnapshot value) {
        ExcelComparisonItem item = baseItem(comparisonId, "ADDED", key, null, value);
        item.setChangesJson(write(Map.of("after", value.values())));
        return item;
    }
    private ExcelComparisonItem removed(Long comparisonId, String key, RowSnapshot value) {
        ExcelComparisonItem item = baseItem(comparisonId, "REMOVED", key, value, null);
        item.setChangesJson(write(Map.of("before", value.values())));
        return item;
    }
    private ExcelComparisonItem issue(Long comparisonId, String key, List<RowSnapshot> left,
                                      List<RowSnapshot> right, String code, String message) {
        ExcelComparisonItem item = baseItem(comparisonId, "ISSUE", key,
                left.isEmpty() ? null : left.get(0), right.isEmpty() ? null : right.get(0));
        item.setChangesJson("{}");
        item.setIssueCode(code);
        item.setIssueMessage(message);
        return item;
    }

    private ExcelComparisonItem baseItem(Long comparisonId, String type, String key,
                                         RowSnapshot left, RowSnapshot right) {
        ExcelComparisonItem item = new ExcelComparisonItem();
        item.setComparisonId(comparisonId);
        item.setChangeType(type);
        item.setMatchKey(key.startsWith("__issue:") ? null : key);
        ExcelTaskRowResult source = right != null ? right.row() : left == null ? null : left.row();
        item.setProductId(source == null ? null : source.getMatchedProductId());
        Object name = right != null ? right.values().get("PRODUCT_NAME")
                : left == null ? null : left.values().get("PRODUCT_NAME");
        item.setProductName(name == null ? null : String.valueOf(name));
        item.setBaseRowNumber(left == null ? null : left.row().getRowNumber());
        item.setNewRowNumber(right == null ? null : right.row().getRowNumber());
        return item;
    }

    @Transactional(readOnly = true)
    public ExcelComparisonView get(Long id, Long operatorId) { return view(require(id, operatorId)); }

    @Transactional(readOnly = true)
    public ExcelComparisonItemPage items(Long id, String type, int page, int size, Long operatorId) {
        require(id, operatorId);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        Page<ExcelComparisonItem> result = StringUtils.hasText(type)
                ? itemRepository.findByComparisonIdAndChangeTypeOrderByIdAsc(id, type.trim().toUpperCase(Locale.ROOT), pageable)
                : itemRepository.findByComparisonIdOrderByIdAsc(id, pageable);
        return new ExcelComparisonItemPage(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.getContent().stream().map(this::itemView).toList());
    }

    @Transactional
    public ExcelComparisonView cancel(Long id, Long operatorId) {
        ExcelComparison value = require(id, operatorId);
        if ("CANCELLED".equals(value.getStatus())) return view(value);
        if (!Set.of("QUEUED", "RUNNING").contains(value.getStatus())) {
            throw new IllegalArgumentException("比较任务已结束，无法取消");
        }
        value.setCancelRequestedAt(LocalDateTime.now());
        value.setStatus("CANCELLED");
        value.setUpdateTime(LocalDateTime.now());
        return view(comparisonRepository.save(value));
    }

    private ExcelComparison require(Long id, Long operatorId) {
        return comparisonRepository.findByIdAndOperatorId(id, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("比较任务不存在或无权访问"));
    }
    private ExcelTask requireTask(Long id, Long operatorId) {
        return taskRepository.findByIdAndOperatorId(id, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("文件任务不存在或无权访问"));
    }
    private void requireVersion(ExcelTask task, Long version) {
        if (version == null || !version.equals(task.getVersion())) throw new IllegalArgumentException("文件任务版本已变化");
        if (!Set.of("READY_FOR_REVIEW", "COMMITTED").contains(task.getStatus())) {
            throw new IllegalArgumentException("文件任务尚未完成审核，不能比较");
        }
    }

    private ExcelComparisonView view(ExcelComparison value) {
        return new ExcelComparisonView(value.getId(), value.getBaseTaskId(), value.getBaseTaskVersion(),
                value.getNewTaskId(), value.getNewTaskVersion(), value.getStatus(),
                value.getProgressCompleted() == null ? 0 : value.getProgressCompleted(),
                value.getProgressTotal() == null ? 0 : value.getProgressTotal(),
                value.getIssueCount() == null ? 0 : value.getIssueCount(),
                readMap(value.getSummaryJson()), value.getErrorCode(), value.getCreateTime(), value.getUpdateTime());
    }
    private ExcelComparisonItemView itemView(ExcelComparisonItem value) {
        return new ExcelComparisonItemView(value.getId(), value.getChangeType(), value.getMatchKey(),
                value.getProductId(), value.getProductName(), value.getBaseRowNumber(), value.getNewRowNumber(),
                readMap(value.getChangesJson()), value.getIssueCode(), value.getIssueMessage());
    }

    private List<String> normalize(List<String> values, Set<String> allowed, List<String> defaults) {
        if (values == null || values.isEmpty()) return defaults;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (!allowed.contains(normalized)) throw new IllegalArgumentException("不支持的比较字段：" + value);
            result.add(normalized);
        }
        return List.copyOf(result);
    }
    private String normalizeKey(String value) {
        if (!StringUtils.hasText(value) || value.trim().length() < 8 || value.trim().length() > 100) {
            throw new IllegalArgumentException("idempotencyKey 长度必须为 8 到 100 个字符");
        }
        return value.trim();
    }
    private boolean same(String field, Object left, Object right) {
        if (Set.of("UNIT_PRICE", "QUANTITY").contains(field)) {
            BigDecimal a = decimal(left); BigDecimal b = decimal(right);
            return a == null ? b == null : b != null && a.compareTo(b) == 0;
        }
        return normalizeText(left).equals(normalizeText(right));
    }
    private BigDecimal percent(String field, Object before, Object after) {
        if (!Set.of("UNIT_PRICE", "QUANTITY").contains(field)) return null;
        BigDecimal a = decimal(before); BigDecimal b = decimal(after);
        if (a == null || b == null || a.signum() == 0) return null;
        return b.subtract(a).multiply(BigDecimal.valueOf(100)).divide(a, 4, RoundingMode.HALF_UP);
    }
    private BigDecimal decimal(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException exception) { return null; }
    }
    private String normalizeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim().replace('　', ' ').toLowerCase(Locale.ROOT);
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }
    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("比较数据序列化失败", exception); }
    }
    private List<String> readList(String json) {
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("比较配置读取失败", exception); }
    }
    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("比较数据读取失败", exception); }
    }
    private record RowSnapshot(ExcelTaskRowResult row, Map<String, Object> values) { }
}
