package com.example.demo.excel.parser;

import com.example.demo.excel.ocr.TableOcrResult;

public record ParsedFile(ParsedWorkbook workbook, TableOcrResult ocrResult) {
}
