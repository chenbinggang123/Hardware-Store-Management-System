package com.example.demo.excel.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ExcelTaskParser {
    static final int MAX_ROWS = 2_000;
    static final int MAX_COLUMNS = 80;

    public ParsedWorkbook parse(String mimeType, byte[] content) {
        try {
            return "text/csv".equals(mimeType) ? parseCsv(content) : parseWorkbook(content);
        } catch (Exception exception) {
            String message = StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName();
            throw new IllegalArgumentException("表格解析失败：" + message, exception);
        }
    }

    private ParsedWorkbook parseWorkbook(byte[] content) throws Exception {
        List<ParsedWorkbook.ParsedSheet> sheets = new ArrayList<>();
        int totalRows = 0;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                List<ParsedWorkbook.ParsedRow> sourceRows = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> cells = readCells(row, formatter, evaluator);
                    if (cells.stream().anyMatch(StringUtils::hasText)) {
                        if (++totalRows > MAX_ROWS) throw new IllegalArgumentException("单个任务最多读取 " + MAX_ROWS + " 行");
                        sourceRows.add(new ParsedWorkbook.ParsedRow(row.getRowNum() + 1, cells));
                    }
                }
                if (!sourceRows.isEmpty()) sheets.add(toSheet(sheetIndex, sheet.getSheetName(), sourceRows));
            }
        }
        if (sheets.isEmpty()) throw new IllegalArgumentException("表格中没有可读取的内容");
        return new ParsedWorkbook(sheets, totalRows);
    }

    private List<String> readCells(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        int last = Math.min(Math.max(row.getLastCellNum(), 0), MAX_COLUMNS);
        List<String> cells = new ArrayList<>(last);
        for (int index = 0; index < last; index++) {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            cells.add(cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim());
        }
        trimTrailingBlanks(cells);
        return cells;
    }

    private ParsedWorkbook parseCsv(byte[] content) {
        List<List<String>> values = csvRows(decodeCsv(content));
        List<ParsedWorkbook.ParsedRow> rows = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            List<String> cells = new ArrayList<>(values.get(index).subList(0, Math.min(values.get(index).size(), MAX_COLUMNS)));
            trimTrailingBlanks(cells);
            if (cells.stream().anyMatch(StringUtils::hasText)) {
                if (rows.size() >= MAX_ROWS) throw new IllegalArgumentException("单个任务最多读取 " + MAX_ROWS + " 行");
                rows.add(new ParsedWorkbook.ParsedRow(index + 1, cells));
            }
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV 中没有可读取的内容");
        return new ParsedWorkbook(List.of(toSheet(0, "CSV", rows)), rows.size());
    }

    private ParsedWorkbook.ParsedSheet toSheet(int index, String name, List<ParsedWorkbook.ParsedRow> sourceRows) {
        ParsedWorkbook.ParsedRow header = sourceRows.get(0);
        List<String> columns = normalizedHeaders(header.cells());
        List<ParsedWorkbook.ParsedRow> dataRows = sourceRows.size() == 1
                ? List.of() : new ArrayList<>(sourceRows.subList(1, sourceRows.size()));
        int dataStart = dataRows.isEmpty() ? header.rowNumber() + 1 : dataRows.get(0).rowNumber();
        return new ParsedWorkbook.ParsedSheet(index, safeSheetName(name), header.rowNumber(), dataStart, columns, dataRows);
    }

    private List<String> normalizedHeaders(List<String> source) {
        List<String> headers = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        int width = Math.max(1, source.size());
        for (int index = 0; index < width; index++) {
            String base = index < source.size() && StringUtils.hasText(source.get(index))
                    ? source.get(index).trim() : "列" + columnName(index);
            int count = counts.merge(base, 1, Integer::sum);
            headers.add(count == 1 ? base : base + "（" + count + "）");
        }
        return headers;
    }

    private String columnName(int index) {
        StringBuilder result = new StringBuilder();
        for (int value = index + 1; value > 0; value = (value - 1) / 26) {
            result.append((char) ('A' + (value - 1) % 26));
        }
        return result.reverse().toString();
    }

    private List<List<String>> csvRows(String source) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (value == '"') {
                if (quoted && i + 1 < source.length() && source.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (value == ',' && !quoted) {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if ((value == '\n' || value == '\r') && !quoted) {
                if (value == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else cell.append(value);
        }
        row.add(cell.toString().trim());
        if (row.stream().anyMatch(StringUtils::hasText)) rows.add(row);
        return rows;
    }

    private String decodeCsv(byte[] content) {
        int offset = content.length >= 3 && content[0] == (byte) 0xef && content[1] == (byte) 0xbb && content[2] == (byte) 0xbf ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content, offset, content.length - offset)).toString();
        } catch (CharacterCodingException ignored) {
            return java.nio.charset.Charset.forName("GB18030").decode(ByteBuffer.wrap(content)).toString();
        }
    }

    private void trimTrailingBlanks(List<String> cells) {
        while (!cells.isEmpty() && !StringUtils.hasText(cells.get(cells.size() - 1))) cells.remove(cells.size() - 1);
    }

    private String safeSheetName(String value) {
        String name = StringUtils.hasText(value) ? value.trim() : "工作表";
        return name.length() <= 100 ? name : name.substring(0, 100);
    }
}
