package com.example.demo.excel.service;

import com.example.demo.entity.Product;
import com.example.demo.excel.dto.ExcelColumnMappingRequest;
import com.example.demo.excel.dto.ExcelColumnMappingView;
import com.example.demo.excel.dto.ExcelMappingRequest;
import com.example.demo.excel.dto.ExcelReviewRowPage;
import com.example.demo.excel.dto.ExcelReviewRowView;
import com.example.demo.excel.dto.ExcelReviewSummary;
import com.example.demo.excel.dto.ProductCandidateView;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskMapping;
import com.example.demo.excel.entity.ExcelTaskRow;
import com.example.demo.excel.entity.ExcelTaskRowResult;
import com.example.demo.excel.entity.ExcelTaskSheet;
import com.example.demo.excel.repository.ExcelTaskMappingRepository;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.excel.repository.ExcelTaskRowRepository;
import com.example.demo.excel.repository.ExcelTaskRowResultRepository;
import com.example.demo.excel.repository.ExcelTaskSheetRepository;
import com.example.demo.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Transactional
public class ExcelTaskMappingService {
    private static final Set<String> TARGET_FIELDS = Set.of(
            "PRODUCT_NAME", "BARCODE", "SPEC", "UNIT", "QUANTITY", "UNIT_PRICE", "AMOUNT",
            "REMARK", "SUPPLIER_SKU", "CUSTOMER_SKU", "IGNORE");

    private final ExcelTaskRepository taskRepository;
    private final ExcelTaskSheetRepository sheetRepository;
    private final ExcelTaskRowRepository rowRepository;
    private final ExcelTaskMappingRepository mappingRepository;
    private final ExcelTaskRowResultRepository resultRepository;
    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;

    public ExcelTaskMappingService(ExcelTaskRepository taskRepository,
                                   ExcelTaskSheetRepository sheetRepository,
                                   ExcelTaskRowRepository rowRepository,
                                   ExcelTaskMappingRepository mappingRepository,
                                   ExcelTaskRowResultRepository resultRepository,
                                   ProductRepository productRepository,
                                   ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.sheetRepository = sheetRepository;
        this.rowRepository = rowRepository;
        this.mappingRepository = mappingRepository;
        this.resultRepository = resultRepository;
        this.productRepository = productRepository;
        this.objectMapper = objectMapper;
    }

    public ExcelReviewSummary apply(Long taskId, Long sheetId, ExcelMappingRequest request, Long operatorId) {
        ExcelTask task = requireTask(taskId, operatorId);
        ExcelTaskSheet sheet = requireSheet(taskId, sheetId);
        if (!Set.of("READY_FOR_MAPPING", "READY_FOR_REVIEW").contains(task.getStatus())) {
            throw new IllegalArgumentException("当前任务状态不能设置字段映射");
        }
        if (request == null || request.mappings() == null || request.mappings().isEmpty()) {
            throw new IllegalArgumentException("请至少设置一个字段映射");
        }
        if (request.expectedVersion() == null) throw new IllegalArgumentException("expectedVersion 不能为空");
        if (!request.expectedVersion().equals(task.getVersion())) throw new ResponseStatusException(
                HttpStatus.CONFLICT, "文件任务已被其他操作更新，请刷新后重试");

        List<String> columns = readStrings(sheet.getColumnsJson());
        List<ValidatedMapping> mappings = validateMappings(request.mappings(), columns, task.getPurpose());
        mappingRepository.deleteByTaskIdAndSheetId(taskId, sheetId);
        resultRepository.deleteByTaskIdAndSheetId(taskId, sheetId);

        LocalDateTime now = LocalDateTime.now();
        List<ExcelTaskMapping> storedMappings = mappings.stream().map(mapping -> {
            ExcelTaskMapping entity = new ExcelTaskMapping();
            entity.setTaskId(taskId);
            entity.setSheetId(sheetId);
            entity.setSourceColumnIndex(mapping.sourceIndex());
            entity.setSourceColumnName(mapping.sourceName());
            entity.setTargetField(mapping.targetField());
            entity.setCreateTime(now);
            return entity;
        }).toList();
        mappingRepository.saveAll(storedMappings);

        List<ExcelTaskRow> sourceRows = rowRepository.findByTaskIdAndSheetIdOrderByRowNumberAsc(taskId, sheetId);
        List<ExcelTaskRowResult> results = new ArrayList<>();
        for (ExcelTaskRow sourceRow : sourceRows) {
            results.add(buildResult(task, sheet, sourceRow, mappings));
        }
        resultRepository.saveAll(results);
        rowRepository.saveAll(sourceRows);

        task.setStatus(mappingRepository.countMappedSheets(taskId) >= task.getSheetCount()
                ? "READY_FOR_REVIEW" : "READY_FOR_MAPPING");
        task.setErrorMessage(null);
        task.setUpdateTime(now);
        taskRepository.saveAndFlush(task);
        return summary(task, sheetId);
    }

    @Transactional(readOnly = true)
    public ExcelReviewSummary summary(Long taskId, Long sheetId, Long operatorId) {
        ExcelTask task = requireTask(taskId, operatorId);
        requireSheet(taskId, sheetId);
        return summary(task, sheetId);
    }

    @Transactional(readOnly = true)
    public ExcelReviewRowPage rows(Long taskId, Long sheetId, int page, int size, Long operatorId) {
        requireTask(taskId, operatorId);
        requireSheet(taskId, sheetId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        var result = resultRepository.findByTaskIdAndSheetIdOrderByRowNumberAsc(
                taskId, sheetId, PageRequest.of(safePage, safeSize));
        List<ExcelReviewRowView> items = result.getContent().stream().map(this::view).toList();
        return new ExcelReviewRowPage(taskId, sheetId, safePage, safeSize,
                result.getTotalElements(), result.getTotalPages(), items);
    }

    private List<ValidatedMapping> validateMappings(List<ExcelColumnMappingRequest> requests,
                                                    List<String> columns, String purpose) {
        Set<Integer> sourceIndexes = new HashSet<>();
        Set<String> targets = new HashSet<>();
        List<ValidatedMapping> result = new ArrayList<>();
        for (ExcelColumnMappingRequest request : requests) {
            if (request == null || request.sourceColumnIndex() < 0 || request.sourceColumnIndex() >= columns.size()) {
                throw new IllegalArgumentException("字段映射包含无效的源列序号");
            }
            if (!sourceIndexes.add(request.sourceColumnIndex())) {
                throw new IllegalArgumentException("同一个源列不能重复映射");
            }
            String target = normalizeTarget(request.targetField());
            if (!"IGNORE".equals(target) && !targets.add(target)) {
                throw new IllegalArgumentException("同一个标准字段不能由多个源列提供");
            }
            result.add(new ValidatedMapping(request.sourceColumnIndex(), columns.get(request.sourceColumnIndex()), target));
        }
        if (!targets.contains("BARCODE") && !targets.contains("PRODUCT_NAME")) {
            throw new IllegalArgumentException("必须映射商品名称或条码中的至少一个字段");
        }
        if ("SUPPLIER_PRICE".equals(purpose) && !targets.contains("UNIT_PRICE")) {
            throw new IllegalArgumentException("供应商价格表必须映射单价字段");
        }
        if (Set.of("INVENTORY_COUNT", "PURCHASE_RECEIPT").contains(purpose) && !targets.contains("QUANTITY")) {
            throw new IllegalArgumentException("库存或收货表必须映射数量字段");
        }
        if ("PRODUCT_IMPORT".equals(purpose) && !targets.contains("PRODUCT_NAME")) {
            throw new IllegalArgumentException("商品导入必须映射商品名称字段");
        }
        return result;
    }

    private ExcelTaskRowResult buildResult(ExcelTask task, ExcelTaskSheet sheet, ExcelTaskRow row,
                                           List<ValidatedMapping> mappings) {
        List<String> cells = readStrings(row.getCellsJson());
        Map<String, Object> normalized = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();
        for (ValidatedMapping mapping : mappings) {
            if ("IGNORE".equals(mapping.targetField())) continue;
            String raw = mapping.sourceIndex() < cells.size() ? trimToNull(cells.get(mapping.sourceIndex())) : null;
            if (Set.of("QUANTITY", "UNIT_PRICE", "AMOUNT").contains(mapping.targetField())) {
                normalized.put(mapping.targetField(), decimal(raw, mapping.targetField(), issues));
            } else {
                normalized.put(mapping.targetField(), raw);
            }
        }
        validateRow(task.getPurpose(), normalized, issues);

        MatchOutcome match = match(normalized);
        String status = issues.isEmpty() ? match.status() : "INVALID";
        if (issues.isEmpty() && "PRODUCT_IMPORT".equals(task.getPurpose())
                && match.product() == null && match.candidates().isEmpty()) {
            status = "READY_TO_CREATE";
        }
        String action = action(task.getPurpose(), match.product(), match.status());
        Map<String, Object> before = match.product() == null ? Map.of() : productSnapshot(match.product());
        Map<String, Object> after = after(task.getPurpose(), normalized, match.product());

        ExcelTaskRowResult result = new ExcelTaskRowResult();
        result.setTaskId(task.getId());
        result.setSheetId(sheet.getId());
        result.setRowId(row.getId());
        result.setRowNumber(row.getRowNumber());
        result.setMatchStatus(status);
        result.setMatchedProductId(match.product() == null ? null : match.product().getId());
        result.setMatchReason(match.reason());
        result.setActionType(action);
        result.setNormalizedJson(write(normalized));
        result.setCandidatesJson(write(match.candidates()));
        result.setBeforeJson(write(before));
        result.setAfterJson(write(after));
        result.setIssuesJson(write(issues));
        row.setReviewStatus(status);
        return result;
    }

    private MatchOutcome match(Map<String, Object> normalized) {
        String barcode = textValue(normalized.get("BARCODE"));
        String name = textValue(normalized.get("PRODUCT_NAME"));
        String spec = textValue(normalized.get("SPEC"));
        if (barcode != null) {
            var barcodeMatch = productRepository.findByBarcode(barcode);
            if (barcodeMatch.isPresent()) {
                return new MatchOutcome("MATCHED", barcodeMatch.get(), "条码精确匹配",
                        List.of(candidate(barcodeMatch.get())));
            }
        }
        if (name == null) return new MatchOutcome("NEEDS_REVIEW", null, "没有可用于匹配的商品名称", List.of());

        List<Product> exactName = productRepository.findTop10ByNameIgnoreCaseOrderByIdAsc(name);
        List<Product> exact = spec == null ? exactName : exactName.stream()
                .filter(product -> spec.equalsIgnoreCase(trimToEmpty(product.getSpec()))).toList();
        if (barcode == null && exact.size() == 1) {
            Product product = exact.get(0);
            String reason = spec == null ? "商品名称精确且唯一" : "商品名称和规格精确且唯一";
            return new MatchOutcome("MATCHED", product, reason, List.of(candidate(product)));
        }
        List<Product> candidates = exact.isEmpty()
                ? productRepository.findTop10ByNameContainingIgnoreCaseOrderByIdAsc(name)
                : exact;
        String reason = barcode != null ? "条码未命中，需要确认商品候选"
                : candidates.isEmpty() ? "未找到商品候选" : "存在多个或非精确候选";
        return new MatchOutcome("NEEDS_REVIEW", null, reason, candidates.stream().map(this::candidate).toList());
    }

    private void validateRow(String purpose, Map<String, Object> values, List<String> issues) {
        if (textValue(values.get("BARCODE")) == null && textValue(values.get("PRODUCT_NAME")) == null) {
            issues.add("商品名称和条码不能同时为空");
        }
        if ("SUPPLIER_PRICE".equals(purpose) && values.get("UNIT_PRICE") == null) {
            issues.add("单价不能为空");
        }
        if (Set.of("INVENTORY_COUNT", "PURCHASE_RECEIPT").contains(purpose)) {
            Object quantity = values.get("QUANTITY");
            if (quantity == null) issues.add("数量不能为空");
            if (quantity instanceof BigDecimal number && number.stripTrailingZeros().scale() > 0) {
                issues.add("库存数量必须是整数");
            }
        }
    }

    private BigDecimal decimal(String value, String field, List<String> issues) {
        if (value == null) return null;
        String cleaned = value.replace(",", "").replace("￥", "").replace("¥", "").trim();
        try {
            BigDecimal number = new BigDecimal(cleaned);
            if (number.signum() < 0) issues.add(field + "不能为负数");
            return number;
        } catch (NumberFormatException exception) {
            issues.add(field + "不是有效数字：" + value);
            return null;
        }
    }

    private String action(String purpose, Product product, String matchStatus) {
        return switch (purpose) {
            case "BUYER_QUOTE" -> "QUOTE_LINE";
            case "SUPPLIER_PRICE" -> "UPDATE_COST_PRICE";
            case "INVENTORY_COUNT" -> "ADJUST_STOCK";
            case "PURCHASE_RECEIPT" -> "RECEIVE_STOCK";
            case "PRODUCT_IMPORT" -> product != null ? "UPDATE_PRODUCT"
                    : "READY_TO_CREATE".equals(matchStatus) ? "CREATE_PRODUCT" : "REVIEW_PRODUCT_MATCH";
            default -> "REVIEW_ONLY";
        };
    }

    private Map<String, Object> after(String purpose, Map<String, Object> normalized, Product product) {
        Map<String, Object> after = new LinkedHashMap<>();
        switch (purpose) {
            case "SUPPLIER_PRICE" -> after.put("costPrice", normalized.get("UNIT_PRICE"));
            case "INVENTORY_COUNT" -> after.put("stock", normalized.get("QUANTITY"));
            case "PURCHASE_RECEIPT" -> {
                BigDecimal current = BigDecimal.valueOf(product == null || product.getStock() == null ? 0 : product.getStock());
                BigDecimal quantity = normalized.get("QUANTITY") instanceof BigDecimal value ? value : BigDecimal.ZERO;
                after.put("stock", current.add(quantity));
            }
            case "PRODUCT_IMPORT" -> {
                after.put("name", normalized.get("PRODUCT_NAME"));
                after.put("barcode", normalized.get("BARCODE"));
                after.put("spec", normalized.get("SPEC"));
                after.put("unit", normalized.get("UNIT"));
                after.put("costPrice", normalized.get("UNIT_PRICE"));
            }
            default -> after.putAll(normalized);
        }
        return after;
    }

    private Map<String, Object> productSnapshot(Product product) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", product.getId());
        value.put("name", product.getName());
        value.put("barcode", product.getBarcode());
        value.put("spec", product.getSpec());
        value.put("unit", product.getUnit());
        value.put("retailPrice", product.getRetailPrice());
        value.put("wholesalePrice", product.getWholesalePrice());
        value.put("costPrice", product.getCostPrice());
        value.put("stock", product.getStock());
        return value;
    }

    private ProductCandidateView candidate(Product product) {
        return new ProductCandidateView(product.getId(), product.getName(), product.getBarcode(), product.getSpec(),
                product.getUnit(), product.getRetailPrice(), product.getWholesalePrice(),
                product.getCostPrice(), product.getStock());
    }

    private ExcelReviewSummary summary(ExcelTask task, Long sheetId) {
        List<ExcelColumnMappingView> mappings = mappingRepository
                .findByTaskIdAndSheetIdOrderBySourceColumnIndexAsc(task.getId(), sheetId).stream()
                .map(mapping -> new ExcelColumnMappingView(mapping.getSourceColumnIndex(),
                        mapping.getSourceColumnName(), mapping.getTargetField())).toList();
        long matched = resultRepository.countByTaskIdAndSheetIdAndMatchStatus(task.getId(), sheetId, "MATCHED");
        long readyToCreate = resultRepository.countByTaskIdAndSheetIdAndMatchStatus(task.getId(), sheetId, "READY_TO_CREATE");
        long needsReview = resultRepository.countByTaskIdAndSheetIdAndMatchStatus(task.getId(), sheetId, "NEEDS_REVIEW");
        long invalid = resultRepository.countByTaskIdAndSheetIdAndMatchStatus(task.getId(), sheetId, "INVALID");
        long excluded = resultRepository.countByTaskIdAndSheetIdAndMatchStatus(task.getId(), sheetId, "EXCLUDED");
        return new ExcelReviewSummary(task.getId(), sheetId, task.getStatus(), task.getVersion(),
                Math.toIntExact(matched + readyToCreate + needsReview + invalid + excluded), matched, readyToCreate,
                needsReview, invalid, excluded, mappings);
    }

    private ExcelReviewRowView view(ExcelTaskRowResult result) {
        return new ExcelReviewRowView(result.getId(), result.getRowId(), result.getRowNumber(), result.getMatchStatus(),
                result.getMatchedProductId(), result.getMatchReason(), result.getActionType(),
                readMap(result.getNormalizedJson()), readCandidates(result.getCandidatesJson()),
                readMap(result.getBeforeJson()), readMap(result.getAfterJson()), readStrings(result.getIssuesJson()));
    }

    private ExcelTask requireTask(Long taskId, Long operatorId) {
        return taskRepository.findByIdAndOperatorId(taskId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("文件任务不存在或无权访问"));
    }

    private ExcelTaskSheet requireSheet(Long taskId, Long sheetId) {
        return sheetRepository.findByIdAndTaskId(sheetId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("工作表不存在"));
    }

    private String normalizeTarget(String value) {
        String target = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
        if (!TARGET_FIELDS.contains(target)) throw new IllegalArgumentException("不支持的标准字段：" + value);
        return target;
    }

    private String textValue(Object value) {
        return value instanceof String text ? trimToNull(text) : null;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("表格处理结果序列化失败", exception);
        }
    }

    private List<String> readStrings(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("表格数据读取失败", exception);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("表格处理结果读取失败", exception);
        }
    }

    private List<ProductCandidateView> readCandidates(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("商品候选读取失败", exception);
        }
    }

    private record ValidatedMapping(int sourceIndex, String sourceName, String targetField) { }

    private record MatchOutcome(String status, Product product, String reason,
                                List<ProductCandidateView> candidates) { }
}
