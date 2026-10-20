package com.example.demo.agent.service;

import com.example.demo.agent.dto.AgentFileView;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.agent.workbook.AgentWorkbookSheets;
import com.example.demo.entity.OperationLog;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.repository.OperationLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Files the agent generated for a user: listing, download checks and user-driven deletion.
 * Agent files never expire; the owner removes them explicitly from “我的文件” or the conversation.
 */
@Service
public class AgentOutputService {
    public static final String AGENT_SOURCE_TYPE = "AGENT_WORKBOOK";
    public static final String MISSING_STATUS = "MISSING";
    static final int DEFAULT_READ_ROWS = 200;
    static final int MAX_READ_ROWS = 500;
    public static final String MISSING_MESSAGE = "文件内容已不在存储中，可能是在切换存储方式前生成的；请删除这条记录后让小五重新生成";
    private static final Logger log = LoggerFactory.getLogger(AgentOutputService.class);

    private final ExcelTaskOutputRepository outputRepository;
    private final AttachmentStorage storage;
    private final OperationLogRepository operationLogRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentOutputService(ExcelTaskOutputRepository outputRepository,
                              AttachmentStorage storage,
                              OperationLogRepository operationLogRepository,
                              ObjectMapper objectMapper) {
        this.outputRepository = outputRepository;
        this.storage = storage;
        this.operationLogRepository = operationLogRepository;
        this.objectMapper = objectMapper;
    }

    public AgentOutputService(ExcelTaskOutputRepository outputRepository, AttachmentStorage storage, ObjectMapper objectMapper) {
        this(outputRepository, storage, null, objectMapper);
    }

    @Transactional(readOnly = true)
    public List<AgentFileView> listMine(Long operatorId) {
        if (operatorId == null) throw new IllegalArgumentException("缺少操作人");
        return outputRepository.findByOperatorIdAndSourceTypeOrderByCreateTimeDesc(operatorId, AGENT_SOURCE_TYPE)
                .stream().map(this::view).toList();
    }

    /** Files attached to one run; callers that already verified the run owner use this directly. */
    @Transactional(readOnly = true)
    public List<AgentFileView> forRun(Long runId) {
        if (runId == null) return List.of();
        return outputRepository.findByRunIdOrderByCreateTimeAsc(runId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<AgentFileView> forRun(Long runId, Long operatorId) {
        if (runId == null || operatorId == null) return List.of();
        return outputRepository.findByRunIdOrderByCreateTimeAsc(runId).stream()
                .filter(item -> operatorId.equals(item.getOperatorId()))
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public AgentFileView get(Long outputId, Long operatorId) {
        return view(require(outputId, operatorId));
    }

    @Transactional(readOnly = true)
    public ExcelTaskOutput requireReady(Long outputId, Long operatorId) {
        ExcelTaskOutput output = require(outputId, operatorId);
        if (output.getExpiresAt() != null && !output.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("文件已过期");
        }
        if (MISSING_STATUS.equals(output.getStatus())) throw new IllegalArgumentException(MISSING_MESSAGE);
        if (!"READY".equals(output.getStatus())) throw new IllegalArgumentException("文件尚未生成完成或生成失败");
        if (!StringUtils.hasText(output.getObjectKey())) throw new IllegalArgumentException("文件内容不存在");
        return output;
    }

    /**
     * Like {@link #requireReady} but also confirms the object still exists in storage. A record whose
     * object is gone (for example generated on container disk before COS was configured) is marked
     * {@code MISSING} so the client stops offering to open it and tells the user to regenerate.
     */
    @Transactional
    public ExcelTaskOutput openForDownload(Long outputId, Long operatorId) {
        ExcelTaskOutput output = requireReady(outputId, operatorId);
        try {
            storage.stat(output.getObjectKey());
            return output;
        } catch (RuntimeException exception) {
            log.warn("小五生成文件对象缺失 outputId={} key={}：{}", output.getId(), output.getObjectKey(), exception.getMessage());
            output.setStatus(MISSING_STATUS);
            output.setErrorMessage(MISSING_MESSAGE);
            outputRepository.save(output);
            throw new IllegalArgumentException(MISSING_MESSAGE);
        }
    }

    @Transactional
    public void delete(Long outputId, Long operatorId) {
        ExcelTaskOutput output = require(outputId, operatorId);
        if (!AGENT_SOURCE_TYPE.equals(output.getSourceType())) {
            throw new IllegalArgumentException("只能删除小五生成的文件；文件任务的输出请在对应文件任务中删除");
        }
        if (StringUtils.hasText(output.getObjectKey())) {
            try {
                storage.delete(output.getObjectKey());
            } catch (RuntimeException exception) {
                // The user asked for the file to go away; a storage hiccup must not keep a dead record alive.
                log.warn("删除小五生成文件对象失败，仍移除记录 outputId={} key={}", output.getId(), output.getObjectKey(), exception);
            }
        }
        outputRepository.delete(output);
        audit(operatorId, "DELETE", "删除小五生成的文件：" + output.getOriginalName() + "（#" + output.getId() + "）");
    }

    /**
     * Reads a generated workbook back as plain cells so the model can apply a change and regenerate it.
     * System sheets, the system “核对说明” column and the SUM footer are left out, because the generator
     * adds them again; the “单据信息” sheet comes back as label/value pairs.
     */
    @Transactional
    public Map<String, Object> readWorkbookContent(Long outputId, Long operatorId, String sheetName, Integer maxRows) {
        ExcelTaskOutput output = openForDownload(outputId, operatorId);
        int rowLimit = maxRows == null ? DEFAULT_READ_ROWS : Math.max(1, Math.min(maxRows, MAX_READ_ROWS));
        String wanted = StringUtils.hasText(sheetName) ? sheetName.trim() : null;
        List<Map<String, Object>> sheets = new ArrayList<>();
        List<Map<String, String>> info = new ArrayList<>();
        boolean receiptLayout = false;
        try (InputStream input = storage.openStream(output.getObjectKey()); Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (Sheet sheet : workbook) {
                String name = sheet.getSheetName();
                if (AgentWorkbookSheets.RECEIPT.equals(name)) receiptLayout = true;
                if (AgentWorkbookSheets.SYSTEM_SHEETS.contains(name)) continue;
                if (AgentWorkbookSheets.INFO.equals(name)) {
                    for (Row row : sheet) {
                        if (row.getRowNum() == 0) continue;
                        String label = text(row, 0, formatter, evaluator);
                        if (!StringUtils.hasText(label)) continue;
                        Map<String, String> entry = new LinkedHashMap<>();
                        entry.put("label", label);
                        entry.put("value", text(row, 1, formatter, evaluator));
                        info.add(entry);
                    }
                    continue;
                }
                if (wanted != null && !wanted.equals(name)) continue;
                Row header = headerRow(sheet, formatter, evaluator);
                if (header == null) continue;
                List<Integer> keptColumns = new ArrayList<>();
                List<String> columns = new ArrayList<>();
                for (int column = 0; column < header.getLastCellNum(); column++) {
                    String title = text(header, column, formatter, evaluator);
                    if (!StringUtils.hasText(title) || AgentWorkbookSheets.NOTE_COLUMN.equals(title)) continue;
                    keptColumns.add(column);
                    columns.add(title);
                }
                List<List<String>> rows = new ArrayList<>();
                boolean truncated = false;
                for (int index = header.getRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                    Row row = sheet.getRow(index);
                    if (row == null) continue;
                    String first = text(row, 0, formatter, evaluator);
                    if (AgentWorkbookSheets.TOTAL_LABEL.equals(first) || first.startsWith("说明：")) continue;
                    List<String> values = new ArrayList<>();
                    for (int column : keptColumns) values.add(text(row, column, formatter, evaluator));
                    if (values.stream().noneMatch(StringUtils::hasText)) continue;
                    if (rows.size() >= rowLimit) {
                        truncated = true;
                        break;
                    }
                    rows.add(values);
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("columns", columns);
                item.put("rows", rows);
                item.put("rowCount", rows.size());
                item.put("truncated", truncated);
                sheets.add(item);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("文件内容无法读取：" + exception.getMessage(), exception);
        }
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException(wanted == null ? "文件里没有可读取的数据工作表" : "文件里没有名为“" + wanted + "”的工作表");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outputId", output.getId());
        result.put("fileName", output.getOriginalName());
        result.put("sheets", sheets);
        result.put("layout", receiptLayout ? "RECEIPT" : "TABLE");
        result.put("info", info);
        result.put("note", "修改时把改动应用到这些行，再调用 generate_excel_workbook 生成新文件；数量、单价、金额列仍需标注角色，原文件会保留");
        return result;
    }

    /** Read-only view of the stored file, including system sheets, notes and totals. */
    @Transactional
    public Map<String, Object> previewWorkbook(Long outputId, Long operatorId, int sheetIndex, int offset, int limit) {
        ExcelTaskOutput output = openForDownload(outputId, operatorId);
        if (sheetIndex < 0 || offset < 0) throw new IllegalArgumentException("工作表或行位置无效");
        int pageSize = Math.max(1, Math.min(limit, 200));
        try (InputStream input = storage.openStream(output.getObjectKey()); Workbook workbook = WorkbookFactory.create(input)) {
            if (sheetIndex >= workbook.getNumberOfSheets()) throw new IllegalArgumentException("工作表不存在");
            List<Map<String, Object>> sheets = new ArrayList<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet item = workbook.getSheetAt(index);
                sheets.add(Map.of("index", index, "name", item.getSheetName(),
                        "rowCount", item.getPhysicalNumberOfRows() == 0 ? 0 : item.getLastRowNum() + 1));
            }
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            int totalRows = sheet.getPhysicalNumberOfRows() == 0 ? 0 : sheet.getLastRowNum() + 1;
            if (offset > 0 && offset >= totalRows) throw new IllegalArgumentException("行位置超出工作表范围");
            int columnCount = 0;
            for (Row row : sheet) columnCount = Math.max(columnCount, row.getLastCellNum());
            for (var merged : sheet.getMergedRegions()) columnCount = Math.max(columnCount, merged.getLastColumn() + 1);
            int shownColumns = Math.min(columnCount, 80);
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<List<String>> rows = new ArrayList<>();
            int end = (int) Math.min(totalRows, (long) offset + pageSize);
            List<Map<String, Object>> formats = new ArrayList<>();
            for (int index = offset; index < end; index++) {
                Row source = sheet.getRow(index);
                List<String> values = new ArrayList<>();
                for (int col = 0; col < shownColumns; col++) {
                    values.add(text(source, col, formatter, evaluator));
                    Cell cell = source == null ? null : source.getCell(col);
                    if (cell != null) {
                        var style = cell.getCellStyle();
                        boolean bold = workbook.getFontAt(style.getFontIndexAsInt()).getBold();
                        if (bold || style.getAlignment() != org.apache.poi.ss.usermodel.HorizontalAlignment.GENERAL || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                                || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                            formats.add(Map.of("row", index, "column", col, "bold", bold,
                                    "numeric", cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                                            || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA,
                                    "alignment", style.getAlignment().name(), "fontSize", workbook.getFontAt(style.getFontIndexAsInt()).getFontHeightInPoints(),
                                    "warning", style.getFillForegroundColor() == org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex()));
                        }
                    }
                }
                rows.add(values);
            }
            List<Map<String, Object>> merges = new ArrayList<>();
            for (var range : sheet.getMergedRegions()) {
                if (range.getLastRow() < offset || range.getFirstRow() >= end || range.getFirstColumn() >= shownColumns) continue;
                merges.add(Map.of("firstRow", range.getFirstRow(), "lastRow", range.getLastRow(),
                        "firstColumn", range.getFirstColumn(), "lastColumn", range.getLastColumn(),
                        "text", text(sheet.getRow(range.getFirstRow()), range.getFirstColumn(), formatter, evaluator)));
            }
            List<Integer> widths = new ArrayList<>();
            boolean receipt = AgentWorkbookSheets.RECEIPT.equals(sheet.getSheetName());
            for (int col = 0; col < shownColumns; col++) widths.add(Math.max(receipt ? 28 : 80, Math.min(340, (int) sheet.getColumnWidthInPixels(col))));
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("outputId", output.getId()); view.put("fileName", output.getOriginalName()); view.put("sheets", sheets);
            view.put("sheetIndex", sheetIndex); view.put("sheetName", sheet.getSheetName());
            view.put("offset", offset); view.put("limit", pageSize); view.put("totalRows", totalRows);
            view.put("columnCount", columnCount); view.put("shownColumns", shownColumns); view.put("columnWidths", widths);
            view.put("columnsTruncated", shownColumns < columnCount); view.put("hasMore", end < totalRows);
            view.put("rows", rows); view.put("merges", merges); view.put("formats", formats);
            view.put("presentation", receipt ? "RECEIPT" : "TABLE");
            return view;
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件预览读取失败，请尝试下载原文件", exception);
        }
    }

    /** Finds the densest row near the top, so title rows in statements are not mistaken for headers. */
    private Row headerRow(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        Row best = null;
        int bestCells = 0;
        int limit = Math.min(sheet.getLastRowNum(), 12);
        for (int rowIndex = 0; rowIndex <= limit; rowIndex++) {
            Row candidate = sheet.getRow(rowIndex);
            if (candidate == null) continue;
            int cells = 0;
            for (int column = 0; column < candidate.getLastCellNum(); column++) {
                if (StringUtils.hasText(text(candidate, column, formatter, evaluator))) cells++;
            }
            if (cells > bestCells) {
                best = candidate;
                bestCells = cells;
            }
        }
        return bestCells == 0 ? null : best;
    }

    private String text(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null || column < 0) return "";
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        try {
            return formatter.formatCellValue(cell, evaluator).trim();
        } catch (RuntimeException exception) {
            return formatter.formatCellValue(cell).trim();
        }
    }

    public AgentFileView view(ExcelTaskOutput output) {
        return AgentFileView.from(output, objectMapper);
    }

    private ExcelTaskOutput require(Long outputId, Long operatorId) {
        if (outputId == null || operatorId == null) throw new IllegalArgumentException("文件不存在或无权访问");
        return outputRepository.findByIdAndOperatorId(outputId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在或无权访问"));
    }

    private void audit(Long operatorId, String action, String detail) {
        if (operationLogRepository == null) return;
        OperationLog entry = new OperationLog();
        entry.setOperatorId(operatorId);
        entry.setModule("AGENT_FILE");
        entry.setAction(action);
        entry.setDetail(detail.length() <= 255 ? detail : detail.substring(0, 255));
        entry.setCreateTime(LocalDateTime.now());
        operationLogRepository.save(entry);
    }
}
