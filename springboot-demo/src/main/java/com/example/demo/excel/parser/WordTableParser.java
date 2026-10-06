package com.example.demo.excel.parser;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Converts tables in Word DOC/DOCX files into the same model used by Excel tasks. */
@Component
public class WordTableParser {
    public ParsedWorkbook parse(String mimeType, byte[] content) throws Exception {
        List<List<List<String>>> tables = "application/msword".equals(mimeType)
                ? legacyTables(content) : docxTables(content);
        List<ParsedWorkbook.ParsedSheet> sheets = new ArrayList<>();
        int total = 0;
        for (int index = 0; index < tables.size(); index++) {
            List<List<String>> source = tables.get(index).stream()
                    .filter(row -> row.stream().anyMatch(StringUtils::hasText)).toList();
            if (source.isEmpty()) continue;
            List<String> columns = headers(source.get(0));
            List<ParsedWorkbook.ParsedRow> rows = new ArrayList<>();
            for (int rowIndex = 1; rowIndex < source.size(); rowIndex++) {
                List<String> cells = new ArrayList<>(source.get(rowIndex));
                while (!cells.isEmpty() && !StringUtils.hasText(cells.get(cells.size() - 1))) cells.remove(cells.size() - 1);
                if (cells.stream().anyMatch(StringUtils::hasText)) {
                    rows.add(new ParsedWorkbook.ParsedRow(rowIndex + 1, cells));
                    if (++total > ExcelTaskParser.MAX_ROWS) throw new IllegalArgumentException("单个任务最多读取 " + ExcelTaskParser.MAX_ROWS + " 行");
                }
            }
            sheets.add(new ParsedWorkbook.ParsedSheet(index, "Word表格" + (index + 1), 1, 2, columns, rows));
        }
        if (sheets.isEmpty()) throw new IllegalArgumentException("Word 文档中没有可处理的表格");
        return new ParsedWorkbook(sheets, total);
    }

    private List<List<List<String>>> docxTables(byte[] content) throws Exception {
        List<List<List<String>>> result = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            for (XWPFTable table : document.getTables()) {
                List<List<String>> rows = table.getRows().stream().map(row -> row.getTableCells().stream()
                        .limit(ExcelTaskParser.MAX_COLUMNS)
                        .map(cell -> cell.getText().replaceAll("\\s+", " ").trim()).toList()).toList();
                result.add(rows);
            }
        }
        return result;
    }

    private List<List<List<String>>> legacyTables(byte[] content) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content));
             WordExtractor extractor = new WordExtractor(document)) {
            for (String paragraph : extractor.getParagraphText()) {
                String value = paragraph.replace("\u0007", "").trim();
                if (!value.contains("\t")) continue;
                String[] parts = value.split("\\t", -1);
                List<String> cells = new ArrayList<>();
                for (int index = 0; index < Math.min(parts.length, ExcelTaskParser.MAX_COLUMNS); index++) cells.add(parts[index].trim());
                rows.add(cells);
            }
        }
        return rows.isEmpty() ? List.of() : List.of(rows);
    }

    private List<String> headers(List<String> source) {
        List<String> result = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        for (int index = 0; index < Math.max(source.size(), 1); index++) {
            String base = index < source.size() && StringUtils.hasText(source.get(index)) ? source.get(index).trim() : "列" + (index + 1);
            int count = counts.merge(base, 1, Integer::sum);
            result.add(count == 1 ? base : base + "（" + count + "）");
        }
        return result;
    }
}
