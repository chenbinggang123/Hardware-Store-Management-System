package com.example.demo.excel.parser;

import com.example.demo.excel.ocr.TableOcrProvider;
import com.example.demo.excel.ocr.TableOcrRequest;
import com.example.demo.excel.ocr.TableOcrResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OcrTableParser {
    private final TableOcrProvider provider;

    public OcrTableParser(TableOcrProvider provider) {
        this.provider = provider;
    }

    public ParsedWorkbook parse(String fileName, String mimeType, byte[] content) {
        return parseDetailed(fileName, mimeType, content).workbook();
    }

    public ParsedFile parseDetailed(String fileName, String mimeType, byte[] content) {
        TableOcrResult result = provider.recognize(new TableOcrRequest(fileName, mimeType, content));
        List<ParsedWorkbook.ParsedSheet> sheets = new ArrayList<>();
        int totalRows = 0;
        for (TableOcrResult.Table table : result.tables().stream()
                .sorted(Comparator.comparingInt(TableOcrResult.Table::index)).toList()) {
            ParsedWorkbook.ParsedSheet sheet = toSheet(table);
            if (sheet == null) continue;
            totalRows += sheet.rows().size();
            if (totalRows > ExcelTaskParser.MAX_ROWS) {
                throw new IllegalArgumentException("单个任务最多读取 " + ExcelTaskParser.MAX_ROWS + " 行");
            }
            sheets.add(sheet);
        }
        if (sheets.isEmpty()) throw new IllegalArgumentException("图片中没有识别到可读取的表格");
        return new ParsedFile(new ParsedWorkbook(sheets, totalRows), result);
    }

    private ParsedWorkbook.ParsedSheet toSheet(TableOcrResult.Table table) {
        if (table.cells() == null || table.cells().isEmpty()) return null;
        int minRow = table.cells().stream().mapToInt(TableOcrResult.Cell::rowStart).min().orElse(0);
        int maxRow = table.cells().stream().mapToInt(TableOcrResult.Cell::rowEnd).max().orElse(minRow);
        int minColumn = table.cells().stream().mapToInt(TableOcrResult.Cell::columnStart).min().orElse(0);
        int maxColumn = table.cells().stream().mapToInt(TableOcrResult.Cell::columnEnd).max().orElse(minColumn);
        int width = maxColumn - minColumn + 1;
        if (width > ExcelTaskParser.MAX_COLUMNS) {
            throw new IllegalArgumentException("单个表格最多读取 " + ExcelTaskParser.MAX_COLUMNS + " 列");
        }

        Map<Integer, List<String>> matrix = new LinkedHashMap<>();
        for (int row = minRow; row <= maxRow; row++) matrix.put(row, blanks(width));
        for (TableOcrResult.Cell cell : table.cells()) {
            List<String> row = matrix.get(cell.rowStart());
            if (row == null) continue;
            int column = cell.columnStart() - minColumn;
            if (column >= 0 && column < row.size()) row.set(column, safeText(cell.rawText()));
        }

        List<Map.Entry<Integer, List<String>>> nonEmptyRows = matrix.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(StringUtils::hasText)).toList();
        if (nonEmptyRows.isEmpty()) return null;
        Map.Entry<Integer, List<String>> header = nonEmptyRows.get(0);
        List<String> columns = normalizedHeaders(header.getValue());
        List<ParsedWorkbook.ParsedRow> rows = new ArrayList<>();
        for (int index = 1; index < nonEmptyRows.size(); index++) {
            Map.Entry<Integer, List<String>> source = nonEmptyRows.get(index);
            List<String> values = new ArrayList<>(source.getValue());
            trimTrailingBlanks(values);
            rows.add(new ParsedWorkbook.ParsedRow(source.getKey() + 1, values));
        }
        int dataStart = rows.isEmpty() ? header.getKey() + 2 : rows.get(0).rowNumber();
        String name = StringUtils.hasText(table.name()) ? table.name().trim() : "识别表格" + (table.index() + 1);
        return new ParsedWorkbook.ParsedSheet(table.index(), safeSheetName(name),
                header.getKey() + 1, dataStart, columns, rows);
    }

    private List<String> blanks(int width) {
        List<String> values = new ArrayList<>(width);
        for (int index = 0; index < width; index++) values.add("");
        return values;
    }

    private List<String> normalizedHeaders(List<String> source) {
        List<String> headers = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        for (int index = 0; index < source.size(); index++) {
            String base = StringUtils.hasText(source.get(index)) ? source.get(index).trim() : "列" + columnName(index);
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

    private void trimTrailingBlanks(List<String> cells) {
        while (!cells.isEmpty() && !StringUtils.hasText(cells.get(cells.size() - 1))) cells.remove(cells.size() - 1);
    }

    private String safeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String safeSheetName(String value) {
        String cleaned = value.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return cleaned.length() <= 100 ? cleaned : cleaned.substring(0, 100);
    }
}
