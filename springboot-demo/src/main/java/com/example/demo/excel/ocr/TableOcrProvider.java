package com.example.demo.excel.ocr;

public interface TableOcrProvider {
    TableOcrResult recognize(TableOcrRequest request);
}
