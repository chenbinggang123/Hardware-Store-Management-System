package com.example.demo.excel.dto;

import java.util.List;

public record ExcelTaskSheetView(Long id, int sheetIndex, String sheetName, int headerRowNumber,
                                 int dataStartRowNumber, int rowCount, int columnCount,
                                 List<String> columns) {
}
