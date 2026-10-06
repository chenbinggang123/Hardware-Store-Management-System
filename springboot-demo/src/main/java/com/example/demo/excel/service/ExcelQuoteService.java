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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
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
    private static final String PDF = "application/pdf";
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
        String format = normalizeFormat(request.outputFormat());
        Customer customer = null;
        if ("CUSTOMER_TYPE_PRICE".equals(mode)) {
            if (request.customerId() == null) throw new IllegalArgumentException("按客户类型报价必须选择客户");
            customer = customerRepository.findById(request.customerId())
                    .orElseThrow(() -> new IllegalArgumentException("客户不存在"));
        }
        String key = normalizeKey(request.idempotencyKey());
        String digest = digest(taskId + "|" + task.getVersion() + "|" + mode + "|" + format + "|"
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
        output.setOutputType("BUYER_QUOTE_" + format);
        output.setOriginalName(fileName(customer, taskId, now, format));
        output.setMimeType("PDF".equals(format) ? PDF : XLSX);
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
        Runnable work = () -> build(outputId, mode, customerId, request.includeUnmatchedRows(), format);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { executor.execute(work); }
            });
        } else {
            executor.execute(work);
        }
        return ExcelOutputView.from(output);
    }

    private void build(Long outputId, String mode, Long customerId, boolean includeUnmatched, String format) {
        ExcelTaskOutput output = outputRepository.findById(outputId).orElse(null);
        if (output == null) return;
        try {
            Customer customer = customerId == null ? null : customerRepository.findById(customerId).orElse(null);
            List<ExcelTaskRowResult> rows = rowRepository.findByTaskIdOrderBySheetIdAscRowNumberAsc(output.getTaskId());
            QuoteBytes quote = "PDF".equals(format)
                    ? pdf(rows, mode, customer, includeUnmatched)
                    : workbook(rows, mode, customer, includeUnmatched);
            String objectKey = "excel-outputs/" + output.getOperatorId() + "/" + output.getTaskId()
                    + "/" + output.getId() + ("PDF".equals(format) ? ".pdf" : ".xlsx");
            storage.put(objectKey, quote.bytes(), "PDF".equals(format) ? PDF : XLSX);
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

    private QuoteBytes pdf(List<ExcelTaskRowResult> rows, String mode, Customer customer,
                           boolean includeUnmatched) {
        List<QuoteLine> lines = new java.util.ArrayList<>();
        int issues = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (ExcelTaskRowResult row : rows) {
            Map<String, Object> normalized = readMap(row.getNormalizedJson());
            if ("EXCLUDED".equals(row.getMatchStatus())) continue;
            if (row.getMatchedProductId() == null || !"MATCHED".equals(row.getMatchStatus())) {
                if (includeUnmatched) {
                    issues++;
                    lines.add(new QuoteLine(row.getSheetId(), safe(normalized.get("PRODUCT_NAME")),
                            safe(normalized.get("UNIT")), nullableInteger(normalized.get("QUANTITY")),
                            null, null));
                }
                continue;
            }
            Product product = productRepository.findById(row.getMatchedProductId())
                    .orElseThrow(() -> new IllegalArgumentException("报价商品不存在：" + row.getMatchedProductId()));
            BigDecimal unitPrice = price(product, mode, customer);
            if (unitPrice == null || unitPrice.signum() < 0) {
                issues++;
                if (includeUnmatched) lines.add(new QuoteLine(row.getSheetId(), safe(normalized.get("PRODUCT_NAME")),
                        safe(normalized.get("UNIT")), nullableInteger(normalized.get("QUANTITY")), null, null));
                continue;
            }
            int quantity = integer(normalized.get("QUANTITY"), 1);
            BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(quantity));
            total = total.add(amount);
            lines.add(new QuoteLine(row.getSheetId(), safe(normalized.get("PRODUCT_NAME")),
                    StringUtils.hasText(safe(normalized.get("UNIT"))) ? safe(normalized.get("UNIT")) : safe(product.getUnit()),
                    quantity, unitPrice, amount));
        }
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            BufferedImage image = quoteImage(lines, customer, mode, total);
            PDPage page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(LosslessFactory.createFromImage(document, image), 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            document.save(buffer);
            return new QuoteBytes(buffer.toByteArray(), lines.size(), issues, total);
        } catch (Exception exception) {
            throw new IllegalStateException("PDF 报价文件生成失败", exception);
        }
    }

    private BufferedImage quoteImage(List<QuoteLine> lines, Customer customer, String mode, BigDecimal total) {
        int width = 1684, height = 1191;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE); g.fillRect(0, 0, width, height);
            Font font = chineseFont();
            g.setColor(new Color(25, 40, 34));
            g.setFont(font.deriveFont(Font.BOLD, 34f));
            drawCentered(g, "配件采购报价明细表", width / 2, 58);
            g.setFont(font.deriveFont(18f));
            g.drawString("客户：" + (customer == null ? "未指定" : safe(customer.getName())), 42, 96);
            g.drawString("计价：" + resolvedMode(mode, customer), 500, 96);
            g.drawString("日期：" + java.time.LocalDate.now(), 1260, 96);
            List<Long> groups = lines.stream().map(QuoteLine::sheetId).distinct().limit(3).toList();
            int gap = 18, margin = 32, groupWidth = (width - margin * 2 - gap * 2) / 3;
            for (int index = 0; index < 3; index++) {
                int x = margin + index * (groupWidth + gap);
                Long groupId = index < groups.size() ? groups.get(index) : null;
                List<QuoteLine> group = index < groups.size()
                        ? lines.stream().filter(line -> line.sheetId().equals(groupId)).toList() : List.of();
                drawGroup(g, font, group, x, 118, groupWidth, 900);
            }
            g.setFont(font.deriveFont(Font.BOLD, 22f));
            g.drawString("报价合计：¥" + total.setScale(2, java.math.RoundingMode.HALF_UP), 1180, 1060);
            g.setFont(font.deriveFont(17f));
            g.setColor(new Color(90, 105, 99));
            g.drawString("说明：价格来自当前商品价格；数量为空时按 1 填写，请交付前核对。", 42, 1110);
            return image;
        } finally { g.dispose(); }
    }

    private void drawGroup(Graphics2D g, Font font, List<QuoteLine> rows, int x, int y, int width, int maxHeight) {
        int[] widths = { width * 45 / 100, width * 11 / 100, width * 12 / 100, width * 16 / 100, width * 16 / 100 };
        int rowHeight = Math.max(35, Math.min(48, maxHeight / Math.max(rows.size() + 1, 1)));
        String[] headers = { "名称", "单位", "数量", "单价", "小计" };
        g.setStroke(new BasicStroke(1f));
        for (int row = 0; row <= rows.size(); row++) {
            int left = x;
            for (int column = 0; column < widths.length; column++) {
                g.setColor(row == 0 ? new Color(225, 238, 247) : Color.WHITE);
                g.fillRect(left, y + row * rowHeight, widths[column], rowHeight);
                g.setColor(new Color(110, 124, 118));
                g.drawRect(left, y + row * rowHeight, widths[column], rowHeight);
                String value = row == 0 ? headers[column] : switch (column) {
                    case 0 -> rows.get(row - 1).name(); case 1 -> rows.get(row - 1).unit();
                    case 2 -> rows.get(row - 1).quantity() == null ? "" : String.valueOf(rows.get(row - 1).quantity());
                    case 3 -> money(rows.get(row - 1).price()); default -> money(rows.get(row - 1).amount()); };
                g.setFont(font.deriveFont(row == 0 ? Font.BOLD : Font.PLAIN, row == 0 ? 17f : 15f));
                g.setColor(new Color(32, 46, 40));
                String fitted = fit(g, value, widths[column] - 10);
                g.drawString(fitted, left + 5, y + row * rowHeight + rowHeight * 2 / 3);
                left += widths[column];
            }
        }
    }

    private Font chineseFont() {
        String[] names = { "Microsoft YaHei", "微软雅黑", "Noto Sans CJK SC", "SimSun", "宋体", Font.SANS_SERIF };
        for (String name : names) {
            Font candidate = new Font(name, Font.PLAIN, 18);
            if (candidate.canDisplay('配') && candidate.canDisplay('件')) return candidate;
        }
        throw new IllegalStateException("服务器缺少可显示中文的字体");
    }

    private void drawCentered(Graphics2D g, String text, int center, int baseline) {
        g.drawString(text, center - g.getFontMetrics().stringWidth(text) / 2, baseline);
    }

    private String fit(Graphics2D g, String value, int maxWidth) {
        String text = safe(value); FontMetrics metrics = g.getFontMetrics();
        if (metrics.stringWidth(text) <= maxWidth) return text;
        while (text.length() > 1 && metrics.stringWidth(text + "…") > maxWidth) text = text.substring(0, text.length() - 1);
        return text + "…";
    }

    private String money(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }

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

    private String normalizeFormat(String value) {
        String format = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "XLSX";
        if (!Set.of("XLSX", "PDF").contains(format)) throw new IllegalArgumentException("输出格式只支持 XLSX 或 PDF");
        return format;
    }

    private String normalizeKey(String value) {
        if (!StringUtils.hasText(value) || value.trim().length() < 8 || value.trim().length() > 100) {
            throw new IllegalArgumentException("idempotencyKey 长度必须为 8 到 100 个字符");
        }
        return value.trim();
    }

    private String fileName(Customer customer, Long taskId, LocalDateTime now, String format) {
        String name = customer == null ? "未指定客户" : safe(customer.getName()).replaceAll("[\\\\/:*?\"<>|]", "_");
        return "报价单-" + name + "-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-" + taskId
                + ("PDF".equals(format) ? ".pdf" : ".xlsx");
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
    private Integer nullableInteger(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) return null;
        try { return Math.max(Integer.parseInt(String.valueOf(value)), 1); }
        catch (NumberFormatException exception) { return null; }
    }
    private String limit(String value) {
        String text = StringUtils.hasText(value) ? value : "未知生成错误";
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private record QuoteBytes(byte[] bytes, int quotedCount, int issueCount, BigDecimal totalAmount) { }
    private record QuoteLine(Long sheetId, String name, String unit, Integer quantity, BigDecimal price, BigDecimal amount) { }
}
