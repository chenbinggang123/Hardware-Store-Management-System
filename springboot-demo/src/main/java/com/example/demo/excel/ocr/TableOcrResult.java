package com.example.demo.excel.ocr;

import java.math.BigDecimal;
import java.util.List;

public record TableOcrResult(String provider, String modelVersion, String requestId,
                             BigDecimal detectedAngle, Integer imageWidth, Integer imageHeight,
                             List<Table> tables, List<String> warnings, String rawResponse) {
    public record Table(int index, String name, List<Cell> cells) {
    }

    public record Cell(int rowStart, int rowEnd, int columnStart, int columnEnd,
                       String rawText, BigDecimal confidence, List<Point> polygon) {
    }

    public record Point(int x, int y) {
    }
}
