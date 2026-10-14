package com.example.demo.finance;

import com.example.demo.agent.dto.AgentFileView;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.entity.Customer;
import com.example.demo.entity.Supplier;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.render.WorkbookRenderSupport;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.finance.dto.StatementExportRequest;
import com.example.demo.finance.entity.AccountDocument;
import com.example.demo.finance.entity.AccountPayment;
import com.example.demo.finance.repository.AccountDocumentRepository;
import com.example.demo.finance.repository.AccountPaymentRepository;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.SupplierRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FinanceStatementService {
    private static final String XLSX = WorkbookRenderSupport.XLSX_MIME;
    private final AccountDocumentRepository documentRepository;
    private final AccountPaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final ExcelTaskOutputRepository outputRepository;
    private final AttachmentStorage storage;
    private final ObjectMapper objectMapper;

    public FinanceStatementService(AccountDocumentRepository documentRepository,
                                   AccountPaymentRepository paymentRepository,
                                   CustomerRepository customerRepository,
                                   SupplierRepository supplierRepository,
                                   ExcelTaskOutputRepository outputRepository,
                                   AttachmentStorage storage,
                                   ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.paymentRepository = paymentRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
        this.outputRepository = outputRepository;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentFileView export(Long operatorId, StatementExportRequest request) {
        if (operatorId == null || request == null) throw new IllegalArgumentException("导出参数不完整");
        String direction = normalizeDirection(request.getDirection());
        Counterparty party = counterparty(direction, request.getCounterpartyId());
        LocalDate from = request.getDateFrom() == null ? LocalDate.now().withDayOfYear(1) : request.getDateFrom();
        LocalDate to = request.getDateTo() == null ? LocalDate.now() : request.getDateTo();
        if (from.isAfter(to)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        String key = normalizeKey(request.getIdempotencyKey());
        String digest = WorkbookRenderSupport.digest((operatorId + "|" + direction + "|" + party.id() + "|" + from + "|" + to)
                .getBytes(StandardCharsets.UTF_8));
        var prior = outputRepository.findByOperatorIdAndIdempotencyKey(operatorId, key);
        if (prior.isPresent()) {
            if (!digest.equals(prior.get().getRequestDigest())) throw new IllegalArgumentException("导出请求编号已用于其他对账单");
            return AgentFileView.from(prior.get(), objectMapper);
        }

        List<AccountDocument> documents = documentRepository.findByOperatorIdOrderByDocumentDateDescIdDesc(operatorId).stream()
                .filter(item -> direction.equals(item.getDirection()))
                .filter(item -> party.type().equals(item.getCounterpartyType()) && party.id().equals(item.getCounterpartyId()))
                .filter(item -> between(item.getDocumentDate(), from, to)).toList();
        String paymentDirection = "RECEIVABLE".equals(direction) ? "RECEIVE" : "PAY";
        List<AccountPayment> payments = paymentRepository.findByOperatorIdOrderByPaymentDateDescIdDesc(operatorId).stream()
                .filter(item -> paymentDirection.equals(item.getDirection()))
                .filter(item -> party.type().equals(item.getCounterpartyType()) && party.id().equals(item.getCounterpartyId()))
                .filter(item -> between(item.getPaymentDate(), from, to)).toList();
        byte[] bytes = workbook(direction, party, from, to, documents, payments);
        LocalDateTime now = LocalDateTime.now();
        ExcelTaskOutput output = new ExcelTaskOutput();
        output.setOperatorId(operatorId);
        output.setSourceType(AgentOutputService.AGENT_SOURCE_TYPE);
        output.setOutputType("FINANCE_STATEMENT");
        output.setOriginalName(safeName(party.name()) + "-往来账对账单-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".xlsx");
        output.setMimeType(XLSX);
        output.setFileSize((long) bytes.length);
        output.setSha256(WorkbookRenderSupport.digest(bytes));
        output.setStatus("READY");
        output.setRequestDigest(digest);
        output.setIdempotencyKey(key);
        output.setCreateTime(now);
        output.setSummaryJson(summary(direction, party, from, to, documents));
        output = outputRepository.saveAndFlush(output);
        String objectKey = "finance-statements/" + operatorId + "/" + output.getId() + "-statement.xlsx";
        try {
            storage.put(objectKey, bytes, XLSX);
            output.setObjectKey(objectKey);
            output = outputRepository.save(output);
        } catch (RuntimeException exception) {
            output.setStatus("FAILED");
            output.setErrorMessage(WorkbookRenderSupport.limit(exception.getMessage(), 1000));
            outputRepository.save(output);
            throw new IllegalArgumentException("对账单保存失败，请稍后重试", exception);
        }
        return AgentFileView.from(output, objectMapper);
    }

    private byte[] workbook(String direction, Counterparty party, LocalDate from, LocalDate to,
                            List<AccountDocument> documents, List<AccountPayment> payments) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            Styles styles = styles(workbook);
            Sheet accounts = workbook.createSheet("往来账");
            title(accounts, styles, party.name() + "往来账对账单", direction, from, to);
            String[] headers = {"单据日期", "到期日", "单号", "账款类型", "单据金额", "已收付", "未结金额", "状态", "备注"};
            header(accounts, 5, headers, styles.header());
            int rowIndex = 6;
            for (AccountDocument item : documents) {
                Row row = accounts.createRow(rowIndex++);
                date(row.createCell(0), item.getDocumentDate(), styles.date());
                date(row.createCell(1), item.getDueDate(), styles.date());
                text(row.createCell(2), item.getDocumentNumber());
                text(row.createCell(3), "RECEIVABLE".equals(item.getDirection()) ? "应收" : "应付");
                number(row.createCell(4), item.getTotalAmount(), styles.money());
                number(row.createCell(5), item.getSettledAmount(), styles.money());
                number(row.createCell(6), item.getOutstandingAmount(), styles.money());
                text(row.createCell(7), status(item.getStatus()));
                text(row.createCell(8), item.getNote());
            }
            Row total = accounts.createRow(rowIndex);
            text(total.createCell(3), "合计");
            for (int column = 4; column <= 6; column++) {
                Cell cell = total.createCell(column);
                cell.setCellFormula("SUM(" + column(column) + "7:" + column(column) + rowIndex + ")");
                cell.setCellStyle(styles.totalMoney());
            }
            finish(accounts, headers.length, rowIndex, new int[]{14, 14, 20, 12, 16, 16, 16, 12, 32});

            Sheet cash = workbook.createSheet("收付款");
            title(cash, styles, party.name() + ("RECEIVABLE".equals(direction) ? "收款记录" : "付款记录"), direction, from, to);
            String[] paymentHeaders = {"日期", "方式", "金额", "未分配金额", "备注"};
            header(cash, 5, paymentHeaders, styles.header());
            rowIndex = 6;
            for (AccountPayment item : payments) {
                Row row = cash.createRow(rowIndex++);
                date(row.createCell(0), item.getPaymentDate(), styles.date());
                text(row.createCell(1), item.getPaymentMethod());
                number(row.createCell(2), item.getAmount(), styles.money());
                number(row.createCell(3), item.getUnallocatedAmount(), styles.money());
                text(row.createCell(4), item.getNote());
            }
            Row paymentTotal = cash.createRow(rowIndex);
            text(paymentTotal.createCell(1), "合计");
            for (int column = 2; column <= 3; column++) {
                Cell cell = paymentTotal.createCell(column);
                cell.setCellFormula("SUM(" + column(column) + "7:" + column(column) + rowIndex + ")");
                cell.setCellStyle(styles.totalMoney());
            }
            finish(cash, paymentHeaders.length, rowIndex, new int[]{14, 18, 16, 16, 36});
            workbook.setForceFormulaRecalculation(true);
            workbook.write(buffer);
            return buffer.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("对账单 Excel 生成失败", exception);
        }
    }

    private void title(Sheet sheet, Styles styles, String title, String direction, LocalDate from, LocalDate to) {
        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(styles.title());
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 4));
        text(sheet.createRow(1).createCell(0), "期间：" + from + " 至 " + to);
        text(sheet.createRow(2).createCell(0), "方向：" + ("RECEIVABLE".equals(direction) ? "客户应收" : "厂商应付"));
        text(sheet.createRow(3).createCell(0), "导出时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    }

    private Styles styles(XSSFWorkbook workbook) {
        Font titleFont = workbook.createFont(); titleFont.setBold(true); titleFont.setFontHeightInPoints((short) 14);
        CellStyle title = workbook.createCellStyle(); title.setFont(titleFont);
        Font headerFont = workbook.createFont(); headerFont.setBold(true); headerFont.setColor(IndexedColors.WHITE.getIndex());
        CellStyle header = workbook.createCellStyle(); header.setFont(headerFont); header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex()); header.setFillPattern(FillPatternType.SOLID_FOREGROUND); header.setAlignment(HorizontalAlignment.CENTER);
        CellStyle date = workbook.createCellStyle(); date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
        CellStyle money = workbook.createCellStyle(); money.setDataFormat(workbook.createDataFormat().getFormat("¥#,##0.00;[Red]-¥#,##0.00"));
        CellStyle totalMoney = workbook.createCellStyle(); totalMoney.cloneStyleFrom(money); totalMoney.setBorderTop(BorderStyle.THIN); Font totalFont = workbook.createFont(); totalFont.setBold(true); totalMoney.setFont(totalFont);
        return new Styles(title, header, date, money, totalMoney);
    }

    private void header(Sheet sheet, int rowIndex, String[] headers, CellStyle style) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < headers.length; i++) { Cell cell = row.createCell(i); cell.setCellValue(headers[i]); cell.setCellStyle(style); }
    }
    private void finish(Sheet sheet, int columns, int lastRow, int[] widths) {
        sheet.createFreezePane(0, 6);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(5, Math.max(5, lastRow), 0, columns - 1));
        sheet.setDisplayGridlines(false);
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    }
    private void text(Cell cell, String value) { cell.setCellValue(WorkbookRenderSupport.safeText(value == null ? "" : value)); }
    private void date(Cell cell, LocalDate value, CellStyle style) { if (value != null) { cell.setCellValue(value); cell.setCellStyle(style); } }
    private void number(Cell cell, BigDecimal value, CellStyle style) { cell.setCellValue(value == null ? 0 : value.doubleValue()); cell.setCellStyle(style); }
    private boolean between(LocalDate value, LocalDate from, LocalDate to) { return value != null && !value.isBefore(from) && !value.isAfter(to); }
    private String status(String value) { return Map.of("UNPAID", "未收付", "PARTIAL", "部分收付", "SETTLED", "已结清").getOrDefault(value, value == null ? "" : value); }
    private String column(int zeroBased) { return String.valueOf((char) ('A' + zeroBased)); }
    private String safeName(String value) { return (StringUtils.hasText(value) ? value : "往来单位").replaceAll("[\\\\/:*?\"<>|]", "_"); }
    private String normalizeDirection(String value) { String result = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : ""; if (!List.of("RECEIVABLE", "PAYABLE").contains(result)) throw new IllegalArgumentException("账款方向不正确"); return result; }
    private String normalizeKey(String value) { if (!StringUtils.hasText(value) || value.trim().length() < 8 || value.trim().length() > 100) throw new IllegalArgumentException("导出请求编号长度必须为 8 到 100 个字符"); return value.trim(); }
    private Counterparty counterparty(String direction, Long id) {
        if (id == null) throw new IllegalArgumentException("请选择客户或厂商");
        if ("RECEIVABLE".equals(direction)) { Customer item = customerRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("客户不存在")); return new Counterparty("CUSTOMER", id, item.getName()); }
        Supplier item = supplierRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("厂商不存在")); return new Counterparty("SUPPLIER", id, item.getName());
    }
    private String summary(String direction, Counterparty party, LocalDate from, LocalDate to, List<AccountDocument> documents) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("format", "XLSX"); value.put("source", "FINANCE_STATEMENT"); value.put("counterparty", party.name());
            value.put("direction", direction); value.put("dateFrom", from.toString()); value.put("dateTo", to.toString());
            value.put("rowCount", documents.size()); value.put("sheets", List.of("往来账", "收付款"));
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) { return "{}"; }
    }
    private record Counterparty(String type, Long id, String name) {}
    private record Styles(CellStyle title, CellStyle header, CellStyle date, CellStyle money, CellStyle totalMoney) {}
}
