package com.example.demo.excel.dto;

import com.example.demo.excel.ocr.TableOcrResult;

import java.math.BigDecimal;
import java.util.List;

public record ExcelTaskOcrView(Long taskId, String provider, String modelVersion, String requestId,
                               BigDecimal detectedAngle, Integer imageWidth, Integer imageHeight,
                               List<TableOcrResult.Table> tables, List<String> warnings) {
}
