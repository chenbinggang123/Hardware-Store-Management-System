package com.example.demo.excel.service;

import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.entity.Customer;
import com.example.demo.entity.Product;
import com.example.demo.excel.dto.ExcelOutputView;
import com.example.demo.excel.dto.ExcelQuoteRequest;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.entity.ExcelTaskRowResult;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.excel.repository.ExcelTaskRowResultRepository;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

@Service
public class ExcelQuoteService {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Set<String> MODES = Set.of(
            "RETAIL_PRICE", "WHOLESALE_PRICE", "OLD_CUSTOMER_PRICE", "CUSTOMER_TYPE_PRICE");

    private final ExcelTaskRepository taskRepository;
    private final ExcelTaskRowResultRepository rowRepository;
    private final ExcelTaskOutputRepository outputRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final AttachmentStorage storage;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public ExcelQuoteService(ExcelTaskRepository taskRepository,
                             ExcelTaskRowResultRepository rowRepository,
                             ExcelTaskOutputRepository outputRepository,
                             ProductRepository productRepository,
                             CustomerRepository customerRepository,
                             AttachmentStorage storage,
                             ObjectMapper objectMapper,
                             @Qualifier("agentTaskExecutor") Executor executor) {
        this.taskRepository = taskRepository;
        this.rowRepository = rowRepository;
        this.outputRepository = outputRepository;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Transactional
    public ExcelOutputView generate(Long taskId, ExcelQuoteRequest request, Long operatorId) {
        ExcelTask task = requireTask(taskId, operatorId);
        if (!"BUYER_QUOTE".equals(task.getPurpose())) {
            throw new IllegalArgumentException("只有买家报价任务可以生成报价文件");
        }
        if (request == null || request.expectedVersion() == null
                || !request.expectedVersion().equals(task.getVersion())) {
            throw new IllegalArgumentException("文件任务版本已变化，请刷新后重试");
        }
        String mode = normalizeMode(request.pricingMode());
        Customer customer = null;
        if ("CUSTOMER_TYPE_PRICE".equals(mode)) {
            if (request.customerId() == null) throw new IllegalArgumentException("按客户类型报价必须选择客户");
            customer = customerRepository.findById(request.customerId())
                    .orElseThrow(() -> new IllegalArgumentException("客户不存在"));
        }
        String key = normalizeKey(request.idempotencyKey());
        String digest = digest(taskId + "|" + task.getVersion() + "|" + mode + "|"
                + request.customerId() + "|" + request.includeUnmatchedRows());
        var prior = outputRepository.findByOperatorIdAndIdempotencyKey(operatorId, key);
        if (prior.isPresent()) {
            if (!digest.equals(prior.get().getRequestDigest())) {
                throw new IllegalArgumentException("报价幂等键已用于不同请求");
            }
            return ExcelOutputView.from(prior.get());
        }
        LocalDateTime now = LocalDateTime.now();
        ExcelTaskOutput output = new ExcelTaskOutput();
        output.setTaskId(taskId);
        output.setOperatorId(operatorId);
        output.setOutputType("BUYER_QUOTE");
        output.setOriginalName(fileName(customer, taskId, now));
        output.setMimeType(XLSX);
        output.setFileSize(0L);
        output.setSha256("");
        output.setStatus("GENERATING");
        output.setRequestDigest(digest);
        output.setIdempotencyKey(key);
        output.setSummaryJson("{}");
        output.setCreateTime(now);
        output.setExpiresAt(now.plusDays(7));
        output = outputRepository.saveAndFlush(output);
        Long outputId = output.getId();
        Long customerId = customer == null ? null : customer.getId();
        Runnable work = () -> build(outputId, mode, customerId, request.includeUnmatchedRows());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { executor.execute(work); }
            });
        } else {
            executor.execute(work);
        }
        return ExcelOutputView.from(output);
    }

    private void build(Long outputId, String mode, Long customerId, boolean includeUnmatched) {
        ExcelTaskOutput output = outputRepository.findById(outputId).orElse(null);
        if (output == null) return;
        try {
            Customer customer = customerId == null ? null : customerRepository.findById(customerId).orElse(null);
            List<ExcelTaskRowResult> rows = rowRepository.findByTaskIdOrderBySheetIdAscRowNumberAsc(output.getTaskId());
            QuoteBytes quote = workbook(rows, mode, customer, includeUnmatched);
            String objectKey = "excel-outputs/" + output.getOperatorId() + "/" + output.getTaskId()
                    + "/" + output.getId() + ".xlsx";
            storage.put(objectKey, quote.bytes(), XLSX);
            output.setObjectKey(objectKey);
            output.setFileSize((long) quote.bytes().length);
            output.setSha256(digest(quote.bytes()));
            output.setSummaryJson(toJson(Map.of(
                    "quotedCount", quote.quotedCount(), "issueCount", quote.issueCount(),
                    "totalAmount", quote.totalAmount())));
            output.setStatus("READY");
            outputRepository.save(output);
        } catch (RuntimeException exception) {
            output.setStatus("FAILED");
            output.setErrorMessage(limit(exception.getMessage()));
            outputRepository.save(output);
        }
    }

    private QuoteBytes workbook(List<ExcelTaskRowResult> rows, String mode, Customer customer,
                                boolean includeUnmatched) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            Sheet detail = workbook.createSheet("报价明细");
            writeRow(detail.createRow(0), List.of("原始行号", "客户商品名称", "匹配商品", "规格", "单位", "数量", "报价单价", "小计", "价格类型"));
            Sheet issues = workbook.createSheet("未匹配与异常");
            writeRow(issues.createRow(0), List.of("原始行号", "原始商品", "问题"));
            int detailIndex = 1;
            int issueIndex = 1;
            BigDecimal total = BigDecimal.ZERO;
            for (ExcelTaskRowResult row : rows) {
                Map<String, Object> normalized = readMap(row.getNormalizedJson());
                if ("EXCLUDED".equals(row.getMatchStatus())) continue;
                if (row.getMatchedProductId() == null || !"MATCHED".equals(row.getMatchStatus())) {
                    if (includeUnmatched) writeRow(issues.createRow(issueIndex++), List.of(
                            row.getRowNumber(), safe(normalized.get("PRODUCT_NAME")),
                            safe(row.getMatchReason())));
                    continue;
                }
                Product product = productRepository.findById(row.getMatchedProductId())
                        .orElseThrow(() -> new IllegalArgumentException("报价商品不存在：" + row.getMatchedProductId()));
                BigDecimal price = price(product, mode, customer);
                if (price == null || price.signum() < 0) {
                    writeRow(issues.createRow(issueIndex++), List.of(row.getRowNumber(), product.getName(), "商品缺少有效报价"));
                    continue;
                }
                int quantity = integer(normalized.get("QUANTITY"), 1);
                BigDecimal amount = price.multiply(BigDecimal.valueOf(quantity));
                total = total.add(amount);
                writeRow(detail.createRow(detailIndex++), List.of(
                        row.getRowNumber(), safe(normalized.get("PRODUCT_NAME")), product.getName(),
                        safe(product.getSpec()), safe(product.getUnit()), quantity,
                        price, amount, resolvedMode(mode, customer)));
            }
            Sheet info = workbook.createSheet("报价说明");
            writeRow(info.createRow(0), List.of("客户", customer == null ? "未指定" : customer.getName()));
            writeRow(info.createRow(1), List.of("计价模式", mode));
            writeRow(info.createRow(2), List.of("生成时间", LocalDateTime.now().toString()));
            writeRow(info.createRow(3), List.of("报价总额", total));
            workbook.write(buffer);
            return new QuoteBytes(buffer.toByteArray(), detailIndex - 1, issueIndex - 1, total);
        } catch (Exception exception) {
            throw new IllegalStateException("报价文件生成失败", exception);
        }
    }

    private void writeRow(Row row, List<?> values) {
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            Cell cell = row.createCell(index);
            if (value instanceof Number number) cell.setCellValue(number.doubleValue());
            else cell.setCellValue(safeSpreadsheetText(value == null ? "" : value.toString()));
        }
    }

    private String safeSpreadsheetText(String value) {
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) return "'" + value;
        return value;
    }

    private BigDecimal price(Product product, String mode, Customer customer) {
        return switch (resolvedMode(mode, customer)) {
            case "WHOLESALE_PRICE" -> product.getWholesalePrice();
            case "OLD_CUSTOMER_PRICE" -> product.getOldCustomerPrice();
            default -> product.getRetailPrice();
        };
    }

    private String resolvedMode(String mode, Customer customer) {
        if (!"CUSTOMER_TYPE_PRICE".equals(mode)) return mode;
        String type = customer == null ? "" : safe(customer.getType());
        if (type.contains("批发")) return "WHOLESALE_PRICE";
        if (type.contains("老客户")) return "OLD_CUSTOMER_PRICE";
        return "RETAIL_PRICE";
    }

    @Transactional(readOnly = true)
    public List<ExcelOutputView> list(Long taskId, Long operatorId) {
        requireTask(taskId, operatorId);
        return outputRepository.findByTaskIdAndOperatorIdOrderByCreateTimeDesc(taskId, operatorId)
                .stream().map(ExcelOutputView::from).toList();
    }

    @Transactional(readOnly = true)
    public ExcelTaskOutput requireReady(Long taskId, Long outputId, Long operatorId) {
        ExcelTaskOutput output = outputRepository.findByIdAndTaskIdAndOperatorId(outputId, taskId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("输出文件不存在或无权访问"));
        if (output.getExpiresAt() != null && !output.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("输出文件已过期");
        }
        if (!"READY".equals(output.getStatus())) throw new IllegalArgumentException("输出文件尚未就绪");
        return output;
    }

    private ExcelTask requireTask(Long taskId, Long operatorId) {
        return taskRepository.findByIdAndOperatorId(taskId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("文件任务不存在或无权访问"));
    }

    private String normalizeMode(String value) {
        String mode = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
        if (!MODES.contains(mode)) throw new IllegalArgumentException("不支持的报价模式");
        return mode;
    }

    private String normalizeKey(String value) {
        if (!StringUtils.hasText(value) || value.trim().length() < 8 || value.trim().length() > 100) {
            throw new IllegalArgumentException("idempotencyKey 长度必须为 8 到 100 个字符");
        }
        return value.trim();
    }

    private String fileName(Customer customer, Long taskId, LocalDateTime now) {
        String name = customer == null ? "未指定客户" : safe(customer.getName()).replaceAll("[\\\\/:*?\"<>|]", "_");
        return "报价单-" + name + "-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-" + taskId + ".xlsx";
    }

    private String digest(String text) { return digest(text.getBytes(StandardCharsets.UTF_8)); }
    private String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }
    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("报价摘要序列化失败", exception); }
    }
    private Map<String, Object> readMap(String json) {
        try { return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("报价行数据读取失败", exception); }
    }
    private String safe(Object value) { return value == null ? "" : String.valueOf(value); }
    private int integer(Object value, int fallback) {
        if (value == null) return fallback;
        try { return Math.max(Integer.parseInt(String.valueOf(value)), 1); }
        catch (NumberFormatException exception) { return fallback; }
    }
    private String limit(String value) {
        String text = StringUtils.hasText(value) ? value : "未知生成错误";
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private record QuoteBytes(byte[] bytes, int quotedCount, int issueCount, BigDecimal totalAmount) { }
}
