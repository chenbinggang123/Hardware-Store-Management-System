package com.example.demo.excel.service;

import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import com.example.demo.entity.OperationLog;
import com.example.demo.entity.Product;
import com.example.demo.excel.dto.ExcelRowReviewRequest;
import com.example.demo.excel.dto.ExcelTaskCommitRequest;
import com.example.demo.excel.dto.ExcelTaskCommitResult;
import com.example.demo.excel.dto.ExcelTaskValidation;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskCommit;
import com.example.demo.excel.entity.ExcelTaskRow;
import com.example.demo.excel.entity.ExcelTaskRowResult;
import com.example.demo.excel.repository.ExcelTaskCommitRepository;
import com.example.demo.excel.repository.ExcelTaskMappingRepository;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.excel.repository.ExcelTaskRowRepository;
import com.example.demo.excel.repository.ExcelTaskRowResultRepository;
import com.example.demo.repository.InventoryLogRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Transactional
public class ExcelTaskCommitService {
    private static final Set<String> REVIEW_DECISIONS = Set.of("SELECT_PRODUCT", "APPROVE_CREATE", "EXCLUDE");

    private final ExcelTaskRepository taskRepository;
    private final ExcelTaskMappingRepository mappingRepository;
    private final ExcelTaskRowRepository rowRepository;
    private final ExcelTaskRowResultRepository resultRepository;
    private final ExcelTaskCommitRepository commitRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryLogRepository inventoryLogRepository;
    private final OperationLogRepository operationLogRepository;
    private final ObjectMapper objectMapper;

    public ExcelTaskCommitService(ExcelTaskRepository taskRepository,
                                  ExcelTaskMappingRepository mappingRepository,
                                  ExcelTaskRowRepository rowRepository,
                                  ExcelTaskRowResultRepository resultRepository,
                                  ExcelTaskCommitRepository commitRepository,
                                  ProductRepository productRepository,
                                  InventoryRepository inventoryRepository,
                                  InventoryLogRepository inventoryLogRepository,
                                  OperationLogRepository operationLogRepository,
                                  ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.mappingRepository = mappingRepository;
        this.rowRepository = rowRepository;
        this.resultRepository = resultRepository;
        this.commitRepository = commitRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryLogRepository = inventoryLogRepository;
        this.operationLogRepository = operationLogRepository;
        this.objectMapper = objectMapper;
    }

    public void reviewRow(Long taskId, Long sheetId, Long resultId,
                          ExcelRowReviewRequest request, Long operatorId) {
        ExcelTask task = requireTask(taskId, operatorId);
        requireVersion(task, request == null ? null : request.expectedVersion());
        if (!Set.of("READY_FOR_MAPPING", "READY_FOR_REVIEW").contains(task.getStatus())) {
            throw new IllegalArgumentException("当前任务状态不能修改审核结果");
        }
        ExcelTaskRowResult result = resultRepository.findByIdAndTaskIdAndSheetId(resultId, taskId, sheetId)
                .orElseThrow(() -> new IllegalArgumentException("审核行不存在"));
        ExcelTaskRow sourceRow = rowRepository.findByIdAndTaskIdAndSheetId(result.getRowId(), taskId, sheetId)
                .orElseThrow(() -> new IllegalArgumentException("原始数据行不存在"));
        String decision = normalizeDecision(request.decision());
        List<String> issues = readStrings(result.getIssuesJson());

        switch (decision) {
            case "EXCLUDE" -> exclude(result, sourceRow, request.reason());
            case "SELECT_PRODUCT" -> selectProduct(task, result, sourceRow, request.productId(), issues);
            case "APPROVE_CREATE" -> approveCreate(task, result, sourceRow, issues);
            default -> throw new IllegalArgumentException("不支持的审核决定");
        }
        resultRepository.save(result);
        rowRepository.save(sourceRow);
        task.setUpdateTime(LocalDateTime.now());
        taskRepository.saveAndFlush(task);
        saveOperation(operatorId, "REVIEW_ROW",
                "文件任务 " + taskId + " 第 " + result.getRowNumber() + " 行：" + decision);
    }

    @Transactional(readOnly = true)
    public ExcelTaskValidation validate(Long taskId, Long expectedVersion, Long operatorId) {
        ExcelTask task = requireTask(taskId, operatorId);
        requireVersion(task, expectedVersion);
        return validateTask(task);
    }

    public ExcelTaskCommitResult commit(Long taskId, ExcelTaskCommitRequest request, Long operatorId) {
        String key = normalizeIdempotencyKey(request == null ? null : request.idempotencyKey());
        ExcelTask task = taskRepository.findForUpdate(taskId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("文件任务不存在或无权访问"));
        var priorByKey = commitRepository.findByOperatorIdAndIdempotencyKey(operatorId, key);
        if (priorByKey.isPresent()) {
            ExcelTaskCommit prior = priorByKey.get();
            if (!prior.getTaskId().equals(taskId)) {
                throw conflict("该幂等键已用于其他文件任务");
            }
            return readCommitResult(prior.getSummaryJson());
        }

        commitRepository.findByTaskId(taskId).ifPresent(existing -> {
            throw conflict("该文件任务已经提交，不能使用新的幂等键重复提交");
        });
        requireVersion(task, request.expectedVersion());
        ExcelTaskValidation validation = validateTask(task);
        if (!validation.valid()) {
            throw new IllegalArgumentException("文件任务尚不能提交：" + String.join("；", validation.blockers()));
        }

        List<ExcelTaskRowResult> rows = resultRepository.findByTaskIdOrderBySheetIdAscRowNumberAsc(taskId);
        ensureBusinessDataUnchanged(task, rows);
        CommitCounts counts = applyRows(task, rows, operatorId);

        LocalDateTime now = LocalDateTime.now();
        ExcelTaskCommit record = new ExcelTaskCommit();
        record.setTaskId(taskId);
        record.setOperatorId(operatorId);
        record.setIdempotencyKey(key);
        record.setRequestVersion(request.expectedVersion());
        record.setStatus("COMPLETED");
        record.setSummaryJson("{}");
        record.setCreateTime(now);
        record = commitRepository.saveAndFlush(record);

        ExcelTaskCommitResult response = new ExcelTaskCommitResult(record.getId(), taskId, "COMPLETED",
                counts.createdProducts(), counts.updatedProducts(), counts.inventoryChanges(),
                validation.excludedRows(), now);
        record.setSummaryJson(write(response));
        commitRepository.save(record);

        task.setStatus("COMMITTED");
        task.setUpdateTime(now);
        taskRepository.save(task);
        saveOperation(operatorId, "COMMIT",
                "提交文件任务 " + taskId + "，新增商品 " + counts.createdProducts()
                        + "，更新商品 " + counts.updatedProducts() + "，库存变更 " + counts.inventoryChanges());
        return response;
    }

    private ExcelTaskValidation validateTask(ExcelTask task) {
        List<ExcelTaskRowResult> rows = resultRepository.findByTaskIdOrderBySheetIdAscRowNumberAsc(task.getId());
        int matched = 0;
        int readyToCreate = 0;
        int needsReview = 0;
        int invalid = 0;
        int excluded = 0;
        for (ExcelTaskRowResult row : rows) {
            switch (row.getMatchStatus()) {
                case "MATCHED" -> matched++;
                case "READY_TO_CREATE" -> readyToCreate++;
                case "NEEDS_REVIEW" -> needsReview++;
                case "INVALID" -> invalid++;
                case "EXCLUDED" -> excluded++;
                default -> invalid++;
            }
        }
        List<String> blockers = new ArrayList<>();
        if (!"READY_FOR_REVIEW".equals(task.getStatus())) blockers.add("所有工作表必须先完成字段映射");
        if (mappingRepository.countMappedSheets(task.getId()) < task.getSheetCount()) blockers.add("仍有工作表未映射");
        if (rows.isEmpty()) blockers.add("没有可提交的处理结果");
        if (needsReview > 0) blockers.add("仍有 " + needsReview + " 行需要人工确认");
        if (invalid > 0) blockers.add("仍有 " + invalid + " 行数据无效");
        if (matched + readyToCreate == 0) blockers.add("没有包含在提交中的有效数据行");
        if (Set.of("BUYER_QUOTE", "UNCLASSIFIED").contains(task.getPurpose())) {
            blockers.add("该文件用途暂时只支持审核预览，尚未配置正式写入目标");
        }
        if ("PRODUCT_IMPORT".equals(task.getPurpose())) {
            for (ExcelTaskRowResult row : rows) {
                if ("READY_TO_CREATE".equals(row.getMatchStatus())) {
                    Map<String, Object> values = readMap(row.getNormalizedJson());
                    if (!StringUtils.hasText(text(values.get("BARCODE")))) {
                        blockers.add("第 " + row.getRowNumber() + " 行新增商品缺少条码");
                    }
                }
            }
        } else if (readyToCreate > 0) {
            blockers.add("当前文件用途不允许新增商品");
        }
        return new ExcelTaskValidation(task.getId(), task.getStatus(), task.getVersion(), blockers.isEmpty(),
                rows.size(), matched + readyToCreate, excluded, matched, readyToCreate,
                needsReview, invalid, List.copyOf(blockers));
    }

    private void selectProduct(ExcelTask task, ExcelTaskRowResult result, ExcelTaskRow sourceRow,
                               Long productId, List<String> issues) {
        if (!issues.isEmpty()) throw new IllegalArgumentException("该行仍有数据错误，不能选择商品：" + String.join("；", issues));
        if (productId == null) throw new IllegalArgumentException("请选择商品");
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在：" + productId));
        Map<String, Object> normalized = readMap(result.getNormalizedJson());
        result.setMatchedProductId(productId);
        result.setMatchStatus("MATCHED");
        result.setMatchReason("人工选择商品");
        result.setCandidatesJson(write(List.of(productCandidate(product))));
        result.setBeforeJson(write(productSnapshot(product)));
        result.setAfterJson(write(after(task.getPurpose(), normalized, product)));
        result.setActionType(action(task.getPurpose(), true));
        sourceRow.setReviewStatus("MATCHED");
    }

    private void approveCreate(ExcelTask task, ExcelTaskRowResult result, ExcelTaskRow sourceRow,
                               List<String> issues) {
        if (!"PRODUCT_IMPORT".equals(task.getPurpose())) {
            throw new IllegalArgumentException("只有商品导入任务可以批准新增商品");
        }
        if (!issues.isEmpty()) throw new IllegalArgumentException("该行仍有数据错误：" + String.join("；", issues));
        Map<String, Object> values = readMap(result.getNormalizedJson());
        String barcode = text(values.get("BARCODE"));
        if (!StringUtils.hasText(barcode)) throw new IllegalArgumentException("新增商品必须提供条码");
        productRepository.findByBarcode(barcode).ifPresent(product -> {
            throw conflict("条码已经存在，请改为选择现有商品");
        });
        result.setMatchedProductId(null);
        result.setMatchStatus("READY_TO_CREATE");
        result.setMatchReason("人工批准新增商品");
        result.setCandidatesJson("[]");
        result.setBeforeJson("{}");
        result.setAfterJson(write(after(task.getPurpose(), values, null)));
        result.setActionType("CREATE_PRODUCT");
        sourceRow.setReviewStatus("READY_TO_CREATE");
    }

    private void exclude(ExcelTaskRowResult result, ExcelTaskRow sourceRow, String reason) {
        if (!StringUtils.hasText(reason)) throw new IllegalArgumentException("排除数据行时必须填写原因");
        result.setMatchStatus("EXCLUDED");
        result.setMatchedProductId(null);
        result.setMatchReason("人工排除：" + reason.trim());
        result.setActionType("SKIP");
        result.setBeforeJson("{}");
        result.setAfterJson("{}");
        sourceRow.setReviewStatus("EXCLUDED");
    }

    private void ensureBusinessDataUnchanged(ExcelTask task, List<ExcelTaskRowResult> rows) {
        for (ExcelTaskRowResult row : rows) {
            if ("EXCLUDED".equals(row.getMatchStatus()) || "READY_TO_CREATE".equals(row.getMatchStatus())) continue;
            Product product = productRepository.findById(row.getMatchedProductId())
                    .orElseThrow(() -> conflict("第 " + row.getRowNumber() + " 行匹配的商品已不存在"));
            Map<String, Object> before = readMap(row.getBeforeJson());
            if (Set.of("INVENTORY_COUNT", "PURCHASE_RECEIPT").contains(task.getPurpose())) {
                int current = inventoryRepository.findByProductId(product.getId())
                        .map(Inventory::getQuantity).orElse(defaultInt(product.getStock()));
                if (decimal(before.get("stock")).compareTo(BigDecimal.valueOf(current)) != 0) {
                    throw conflict("第 " + row.getRowNumber() + " 行库存已变化，请重新生成预览");
                }
            } else if ("SUPPLIER_PRICE".equals(task.getPurpose())) {
                if (decimal(before.get("costPrice")).compareTo(decimal(product.getCostPrice())) != 0) {
                    throw conflict("第 " + row.getRowNumber() + " 行商品进价已变化，请重新生成预览");
                }
            } else if ("PRODUCT_IMPORT".equals(task.getPurpose())) {
                if (!same(text(before.get("barcode")), product.getBarcode())
                        || !same(text(before.get("name")), product.getName())
                        || !same(text(before.get("spec")), product.getSpec())
                        || !same(text(before.get("unit")), product.getUnit())
                        || decimal(before.get("costPrice")).compareTo(decimal(product.getCostPrice())) != 0) {
                    throw conflict("第 " + row.getRowNumber() + " 行商品资料已变化，请重新生成预览");
                }
            }
        }
    }

    private CommitCounts applyRows(ExcelTask task, List<ExcelTaskRowResult> rows, Long operatorId) {
        int created = 0;
        int updated = 0;
        int inventoryChanges = 0;
        for (ExcelTaskRowResult row : rows) {
            if ("EXCLUDED".equals(row.getMatchStatus())) continue;
            Map<String, Object> values = readMap(row.getNormalizedJson());
            switch (task.getPurpose()) {
                case "SUPPLIER_PRICE" -> {
                    Product product = requiredProduct(row);
                    product.setCostPrice(decimal(values.get("UNIT_PRICE")));
                    productRepository.save(product);
                    updated++;
                }
                case "PRODUCT_IMPORT" -> {
                    if ("READY_TO_CREATE".equals(row.getMatchStatus())) {
                        createProduct(values);
                        created++;
                    } else {
                        updateProduct(requiredProduct(row), values);
                        updated++;
                    }
                }
                case "INVENTORY_COUNT" -> {
                    changeInventory(requiredProduct(row), integer(values.get("QUANTITY")), false,
                            task.getId(), operatorId, "Excel 盘点导入");
                    inventoryChanges++;
                }
                case "PURCHASE_RECEIPT" -> {
                    changeInventory(requiredProduct(row), integer(values.get("QUANTITY")), true,
                            task.getId(), operatorId, "Excel 到货导入");
                    inventoryChanges++;
                }
                default -> throw new IllegalArgumentException("当前文件用途不支持正式提交");
            }
        }
        return new CommitCounts(created, updated, inventoryChanges);
    }

    private Product createProduct(Map<String, Object> values) {
        String barcode = requiredText(values, "BARCODE", "商品条码");
        productRepository.findByBarcode(barcode).ifPresent(product -> {
            throw conflict("商品条码已存在：" + barcode);
        });
        Product product = new Product();
        product.setName(requiredText(values, "PRODUCT_NAME", "商品名称"));
        product.setBarcode(barcode);
        product.setSpec(text(values.get("SPEC")));
        product.setUnit(text(values.get("UNIT")));
        product.setCostPrice(decimalOrNull(values.get("UNIT_PRICE")));
        product.setRetailPrice(BigDecimal.ZERO);
        product.setWholesalePrice(BigDecimal.ZERO);
        product.setOldCustomerPrice(BigDecimal.ZERO);
        product.setStock(0);
        product.setStatus(1);
        product.setCreateTime(LocalDateTime.now());
        product = productRepository.save(product);
        Inventory inventory = new Inventory();
        inventory.setProductId(product.getId());
        inventory.setQuantity(0);
        inventory.setWarningThreshold(10);
        inventory.setLastUpdateTime(LocalDateTime.now());
        inventoryRepository.save(inventory);
        return product;
    }

    private void updateProduct(Product product, Map<String, Object> values) {
        setTextIfPresent(values, "PRODUCT_NAME", product::setName);
        setTextIfPresent(values, "BARCODE", value -> {
            productRepository.findByBarcode(value).ifPresent(existing -> {
                if (!existing.getId().equals(product.getId())) throw conflict("商品条码已存在：" + value);
            });
            product.setBarcode(value);
        });
        setTextIfPresent(values, "SPEC", product::setSpec);
        setTextIfPresent(values, "UNIT", product::setUnit);
        if (values.get("UNIT_PRICE") != null) product.setCostPrice(decimal(values.get("UNIT_PRICE")));
        productRepository.save(product);
    }

    private void changeInventory(Product product, int sourceQuantity, boolean increment,
                                 Long taskId, Long operatorId, String remark) {
        Inventory inventory = inventoryRepository.findByProductId(product.getId()).orElseGet(() -> {
            Inventory created = new Inventory();
            created.setProductId(product.getId());
            created.setQuantity(defaultInt(product.getStock()));
            created.setLocationId(product.getLocationId());
            created.setWarningThreshold(10);
            return created;
        });
        int before = defaultInt(inventory.getQuantity());
        int after = increment ? Math.addExact(before, sourceQuantity) : sourceQuantity;
        inventory.setQuantity(after);
        inventory.setLastUpdateTime(LocalDateTime.now());
        inventoryRepository.save(inventory);
        product.setStock(after);
        productRepository.save(product);

        InventoryLog log = new InventoryLog();
        log.setProductId(product.getId());
        log.setProductName(product.getName());
        log.setChangeType(increment ? "采购入库" : "盘点");
        log.setQuantity(after - before);
        log.setBeforeQuantity(before);
        log.setAfterQuantity(after);
        log.setOperatorId(operatorId);
        log.setRelatedOrderId(taskId);
        log.setRemark(remark);
        log.setCreateTime(LocalDateTime.now());
        inventoryLogRepository.save(log);
    }

    private Product requiredProduct(ExcelTaskRowResult row) {
        if (row.getMatchedProductId() == null) throw new IllegalArgumentException("第 " + row.getRowNumber() + " 行未选择商品");
        return productRepository.findById(row.getMatchedProductId())
                .orElseThrow(() -> conflict("第 " + row.getRowNumber() + " 行商品已不存在"));
    }

    private String action(String purpose, boolean matched) {
        return switch (purpose) {
            case "BUYER_QUOTE" -> "QUOTE_LINE";
            case "SUPPLIER_PRICE" -> "UPDATE_COST_PRICE";
            case "INVENTORY_COUNT" -> "ADJUST_STOCK";
            case "PURCHASE_RECEIPT" -> "RECEIVE_STOCK";
            case "PRODUCT_IMPORT" -> matched ? "UPDATE_PRODUCT" : "CREATE_PRODUCT";
            default -> "REVIEW_ONLY";
        };
    }

    private Map<String, Object> after(String purpose, Map<String, Object> values, Product product) {
        Map<String, Object> after = new LinkedHashMap<>();
        switch (purpose) {
            case "SUPPLIER_PRICE" -> after.put("costPrice", values.get("UNIT_PRICE"));
            case "INVENTORY_COUNT" -> after.put("stock", values.get("QUANTITY"));
            case "PURCHASE_RECEIPT" -> after.put("stock",
                    BigDecimal.valueOf(defaultInt(product == null ? null : product.getStock()))
                            .add(decimal(values.get("QUANTITY"))));
            case "PRODUCT_IMPORT" -> {
                after.put("name", values.get("PRODUCT_NAME"));
                after.put("barcode", values.get("BARCODE"));
                after.put("spec", values.get("SPEC"));
                after.put("unit", values.get("UNIT"));
                after.put("costPrice", values.get("UNIT_PRICE"));
            }
            default -> after.putAll(values);
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

    private Map<String, Object> productCandidate(Product product) {
        return productSnapshot(product);
    }

    private ExcelTask requireTask(Long taskId, Long operatorId) {
        return taskRepository.findByIdAndOperatorId(taskId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("文件任务不存在或无权访问"));
    }

    private void requireVersion(ExcelTask task, Long expectedVersion) {
        if (expectedVersion == null) throw new IllegalArgumentException("expectedVersion 不能为空");
        if (!expectedVersion.equals(task.getVersion())) throw conflict("文件任务已更新，请刷新后重试");
    }

    private String normalizeDecision(String value) {
        String decision = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
        if (!REVIEW_DECISIONS.contains(decision)) throw new IllegalArgumentException("不支持的审核决定：" + value);
        return decision;
    }

    private String normalizeIdempotencyKey(String value) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("idempotencyKey 不能为空");
        String key = value.trim();
        if (key.length() < 8 || key.length() > 100) throw new IllegalArgumentException("idempotencyKey 长度必须为 8 到 100 个字符");
        return key;
    }

    private ExcelTaskCommitResult readCommitResult(String json) {
        try {
            return objectMapper.readValue(json, ExcelTaskCommitResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("提交结果读取失败", exception);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("表格处理结果读取失败", exception);
        }
    }

    private List<String> readStrings(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("表格问题信息读取失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Excel 任务结果序列化失败", exception);
        }
    }

    private void saveOperation(Long operatorId, String action, String detail) {
        OperationLog log = new OperationLog();
        log.setOperatorId(operatorId);
        log.setModule("EXCEL_TASK");
        log.setAction(action);
        log.setDetail(detail);
        log.setCreateTime(LocalDateTime.now());
        operationLogRepository.save(log);
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("数值格式无效：" + value);
        }
    }

    private BigDecimal decimalOrNull(Object value) {
        return value == null ? null : decimal(value);
    }

    private int integer(Object value) {
        BigDecimal number = decimal(value).stripTrailingZeros();
        if (number.scale() > 0 || number.signum() < 0 || number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("库存数量必须是非负整数");
        }
        return number.intValueExact();
    }

    private String text(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private String requiredText(Map<String, Object> values, String key, String label) {
        String value = text(values.get(key));
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(label + "不能为空");
        return value;
    }

    private void setTextIfPresent(Map<String, Object> values, String key,
                                  java.util.function.Consumer<String> setter) {
        if (values.containsKey(key) && values.get(key) != null) setter.accept(text(values.get(key)));
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record CommitCounts(int createdProducts, int updatedProducts, int inventoryChanges) { }
}
