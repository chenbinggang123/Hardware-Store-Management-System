package com.example.demo.excel.service;

import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.excel.dto.ExcelOutputView;
import com.example.demo.excel.dto.ExcelWorkbookExportRequest;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.entity.ExcelTaskRow;
import com.example.demo.excel.entity.ExcelTaskSheet;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.excel.repository.ExcelTaskRowRepository;
import com.example.demo.excel.repository.ExcelTaskSheetRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

@Service
public class ExcelWorkbookExportService {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final ExcelTaskRepository taskRepository;
    private final ExcelTaskSheetRepository sheetRepository;
    private final ExcelTaskRowRepository rowRepository;
    private final ExcelTaskOutputRepository outputRepository;
    private final AttachmentStorage storage;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public ExcelWorkbookExportService(ExcelTaskRepository taskRepository,
                                      ExcelTaskSheetRepository sheetRepository,
                                      ExcelTaskRowRepository rowRepository,
                                      ExcelTaskOutputRepository outputRepository,
                                      AttachmentStorage storage,
                                      ObjectMapper objectMapper,
                                      @Qualifier("agentTaskExecutor") Executor executor) {
        this.taskRepository = taskRepository;
        this.sheetRepository = sheetRepository;
        this.rowRepository = rowRepository;
        this.outputRepository = outputRepository;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Transactional
    public ExcelOutputView generate(Long taskId, ExcelWorkbookExportRequest request, Long operatorId) {
        ExcelTask task = taskRepository.findByIdAndOperatorId(taskId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("文件任务不存在或无权访问"));
        if (request == null || request.expectedVersion() == null || !request.expectedVersion().equals(task.getVersion())) {
            throw new IllegalArgumentException("文件任务版本已变化，请刷新后重试");
        }
        String key = normalizeKey(request.idempotencyKey());
        String requestDigest = digest((taskId + "|" + task.getVersion() + "|NORMALIZED_XLSX").getBytes(StandardCharsets.UTF_8));
        var prior = outputRepository.findByOperatorIdAndIdempotencyKey(operatorId, key);
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) throw new IllegalArgumentException("导出幂等键已用于不同请求");
            return ExcelOutputView.from(prior.get());
        }
        LocalDateTime now = LocalDateTime.now();
        ExcelTaskOutput output = new ExcelTaskOutput();
        output.setTaskId(taskId);
        output.setOperatorId(operatorId);
        output.setOutputType("NORMALIZED_XLSX");
        output.setOriginalName(baseName(task.getOriginalName()) + "-可编辑表格-"
                + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".xlsx");
        output.setMimeType(XLSX);
        output.setFileSize(0L);
        output.setSha256("");
        output.setStatus("GENERATING");
        output.setRequestDigest(requestDigest);
        output.setIdempotencyKey(key);
        output.setSummaryJson("{}");
        output.setCreateTime(now);
        output.setExpiresAt(now.plusDays(7));
        output = outputRepository.saveAndFlush(output);
        Long outputId = output.getId();
        Runnable work = () -> build(outputId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { executor.execute(work); }
            });
        } else executor.execute(work);
        return ExcelOutputView.from(output);
    }

    private void build(Long outputId) {
        ExcelTaskOutput output = outputRepository.findById(outputId).orElse(null);
        if (output == null) return;
        try {
            byte[] bytes = workbook(output.getTaskId());
            String objectKey = "excel-outputs/" + output.getOperatorId() + "/" + output.getTaskId()
                    + "/" + output.getId() + "-normalized.xlsx";
            storage.put(objectKey, bytes, XLSX);
            output.setObjectKey(objectKey);
            output.setFileSize((long) bytes.length);
            output.setSha256(digest(bytes));
            output.setSummaryJson("{\"format\":\"XLSX\",\"source\":\"NORMALIZED_FILE_TASK\"}");
            output.setStatus("READY");
            outputRepository.save(output);
        } catch (RuntimeException exception) {
            output.setStatus("FAILED");
            output.setErrorMessage(limit(exception.getMessage()));
            outputRepository.save(output);
        }
    }

    private byte[] workbook(Long taskId) {
        List<ExcelTaskSheet> sourceSheets = sheetRepository.findByTaskIdOrderBySheetIndexAsc(taskId);
        if (sourceSheets.isEmpty()) throw new IllegalArgumentException("文件任务中没有可导出的表格");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            Set<String> names = new HashSet<>();
            for (ExcelTaskSheet source : sourceSheets) {
                List<String> headers = read(source.getColumnsJson());
                String name = uniqueSheetName(source.getSheetName(), names);
                Sheet sheet = workbook.createSheet(name);
                Row header = sheet.createRow(0);
                for (int column = 0; column < headers.size(); column++) {
                    Cell cell = header.createCell(column);
                    cell.setCellValue(safeText(headers.get(column)));
                    cell.setCellStyle(headerStyle);
                }
                List<ExcelTaskRow> rows = rowRepository.findByTaskIdAndSheetIdOrderByRowNumberAsc(taskId, source.getId());
                int targetRow = 1;
                for (ExcelTaskRow sourceRow : rows) {
                    List<String> values = read(sourceRow.getCellsJson());
                    Row row = sheet.createRow(targetRow++);
                    for (int column = 0; column < values.size(); column++) {
                        String headerName = column < headers.size() ? headers.get(column) : "";
                        writeCell(row.createCell(column), values.get(column), headerName);
                    }
                }
                sheet.createFreezePane(0, 1);
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, targetRow - 1), 0, Math.max(0, headers.size() - 1)));
                for (int column = 0; column < headers.size(); column++) {
                    sheet.autoSizeColumn(column);
                    sheet.setColumnWidth(column, Math.min(Math.max(sheet.getColumnWidth(column) + 512, 2560), 12000));
                }
            }
            workbook.write(buffer);
            return buffer.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("可编辑 Excel 生成失败", exception);
        }
    }

    private void writeCell(Cell cell, String value, String header) {
        String text = value == null ? "" : value.trim();
        if (isNumericHeader(header) && text.matches("-?\\d+(\\.\\d+)?")) {
            try { cell.setCellValue(new java.math.BigDecimal(text).doubleValue()); return; }
            catch (NumberFormatException ignored) { }
        }
        cell.setCellValue(safeText(text));
    }

    private boolean isNumericHeader(String value) {
        String header = value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return header.matches(".*(数量|单价|价格|金额|合计|小计|库存|成本|售价).*?");
    }

    private List<String> read(String json) {
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("表格数据读取失败", exception); }
    }

    private String uniqueSheetName(String value, Set<String> used) {
        String base = WorkbookUtil.createSafeSheetName(StringUtils.hasText(value) ? value : "表格");
        if (!StringUtils.hasText(base)) base = "表格";
        base = base.length() <= 31 ? base : base.substring(0, 31);
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            String tail = "-" + suffix++;
            candidate = base.substring(0, Math.min(base.length(), 31 - tail.length())) + tail;
        }
        return candidate;
    }

    private String safeText(String value) {
        String text = value == null ? "" : value;
        return !text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
    }

    private String baseName(String value) {
        String name = StringUtils.hasText(value) ? value.replaceAll("[\\\\/:*?\"<>|]", "_") : "文件";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String normalizeKey(String value) {
        if (!StringUtils.hasText(value) || value.trim().length() < 8 || value.trim().length() > 100) {
            throw new IllegalArgumentException("idempotencyKey 长度必须为 8 到 100 个字符");
        }
        return value.trim();
    }

    private String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }

    private String limit(String value) {
        String text = StringUtils.hasText(value) ? value : "未知生成错误";
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }
}
