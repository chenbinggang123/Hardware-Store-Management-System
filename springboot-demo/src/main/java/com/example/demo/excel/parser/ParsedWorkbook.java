package com.example.demo.excel.parser;

import java.util.List;

public record ParsedWorkbook(List<ParsedSheet> sheets, int totalRows) {
    public record ParsedSheet(int index, String name, int headerRowNumber, int dataStartRowNumber,
                              List<String> columns, List<ParsedRow> rows) {
    }

    public record ParsedRow(int rowNumber, List<String> cells) {
    }
}
