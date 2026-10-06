package com.example.demo.excel.ocr;

public record TableOcrRequest(String fileName, String mimeType, byte[] content) {
}
