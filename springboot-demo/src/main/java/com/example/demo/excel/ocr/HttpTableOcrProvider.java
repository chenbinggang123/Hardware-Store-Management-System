package com.example.demo.excel.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls a small OCR adapter service instead of coupling business code to one cloud SDK.
 * The adapter may wrap Tencent Table OCR, PaddleOCR or WPS image conversion, but must
 * return the stable cell contract parsed by {@link #parseResponse(String)}.
 */
@Component
public class HttpTableOcrProvider implements TableOcrProvider {
    private final String url;
    private final String configuredProvider;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpTableOcrProvider(
            @Value("${table.ocr.url:}") String url,
            @Value("${table.ocr.provider:http-adapter}") String configuredProvider,
            @Value("${table.ocr.timeout-seconds:60}") int timeoutSeconds,
            ObjectMapper objectMapper) {
        this.url = url;
        this.configuredProvider = configuredProvider;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.min(Math.max(timeoutSeconds, 1), 10)));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(timeoutSeconds, 1)));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public TableOcrResult recognize(TableOcrRequest request) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("图片表格 OCR 尚未配置，请设置 TABLE_OCR_URL");
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(request.content()) {
            @Override public String getFilename() { return request.fileName(); }
        });
        body.add("mimeType", request.mimeType());
        String response = restClient.post().uri(url)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body).retrieve().body(String.class);
        return parseResponse(response);
    }

    TableOcrResult parseResponse(String response) {
        try {
            if (!StringUtils.hasText(response)) throw new IllegalStateException("OCR 服务返回空结果");
            JsonNode root = objectMapper.readTree(response);
            JsonNode payload = root.path("data").isObject() ? root.path("data")
                    : root.path("Response").isObject() ? root.path("Response") : root;
            List<TableOcrResult.Table> tables = parseTables(payload);
            if (tables.isEmpty() || tables.stream().allMatch(table -> table.cells().isEmpty())) {
                throw new IllegalStateException("OCR 服务没有返回可识别的表格单元格");
            }
            String provider = text(payload, "provider", "Provider");
            if (!StringUtils.hasText(provider)) provider = configuredProvider;
            return new TableOcrResult(provider,
                    text(payload, "modelVersion", "ModelVersion"),
                    text(payload, "requestId", "RequestId"),
                    decimal(payload, "detectedAngle", "angle", "Angle"),
                    integer(payload, "imageWidth", "width", "ImageWidth"),
                    integer(payload, "imageHeight", "height", "ImageHeight"),
                    tables, strings(payload.path("warnings")), response);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("OCR 服务返回格式无法解析：" + safeMessage(exception), exception);
        }
    }

    private List<TableOcrResult.Table> parseTables(JsonNode payload) {
        JsonNode tableNodes = firstArray(payload, "tables", "tableDetections", "TableDetections");
        List<TableOcrResult.Table> tables = new ArrayList<>();
        if (tableNodes != null) {
            int fallbackIndex = 0;
            for (JsonNode table : tableNodes) {
                int index = integer(table, "index", "tableIndex", "TableIndex") == null
                        ? fallbackIndex : integer(table, "index", "tableIndex", "TableIndex");
                String name = text(table, "name", "title", "Name");
                tables.add(new TableOcrResult.Table(index,
                        StringUtils.hasText(name) ? name : "识别表格" + (index + 1),
                        parseCells(firstArray(table, "cells", "Cells"))));
                fallbackIndex++;
            }
            return tables;
        }
        JsonNode cells = firstArray(payload, "cells", "Cells");
        if (cells != null) tables.add(new TableOcrResult.Table(0, "识别表格1", parseCells(cells)));
        return tables;
    }

    private List<TableOcrResult.Cell> parseCells(JsonNode nodes) {
        List<TableOcrResult.Cell> cells = new ArrayList<>();
        if (nodes == null) return cells;
        for (JsonNode cell : nodes) {
            Integer rowStart = integer(cell, "rowStart", "row", "rowTl", "RowTl");
            Integer columnStart = integer(cell, "columnStart", "column", "colTl", "ColTl");
            if (rowStart == null || columnStart == null) continue;
            Integer rowEnd = integer(cell, "rowEnd", "rowBr", "RowBr");
            Integer columnEnd = integer(cell, "columnEnd", "colBr", "ColBr");
            String value = text(cell, "rawText", "text", "Text");
            cells.add(new TableOcrResult.Cell(rowStart,
                    rowEnd == null ? rowStart : rowEnd,
                    columnStart, columnEnd == null ? columnStart : columnEnd,
                    value == null ? "" : value.trim(),
                    decimal(cell, "confidence", "Confidence"),
                    points(firstArray(cell, "polygon", "Polygon"))));
        }
        return cells;
    }

    private List<TableOcrResult.Point> points(JsonNode nodes) {
        List<TableOcrResult.Point> points = new ArrayList<>();
        if (nodes == null) return points;
        for (JsonNode point : nodes) {
            if (point.isArray() && point.size() >= 2) {
                points.add(new TableOcrResult.Point(point.get(0).asInt(), point.get(1).asInt()));
            } else if (point.isObject()) {
                Integer x = integer(point, "x", "X");
                Integer y = integer(point, "y", "Y");
                if (x != null && y != null) points.add(new TableOcrResult.Point(x, y));
            }
        }
        return points;
    }

    private JsonNode firstArray(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) if (node.path(name).isArray()) return node.path(name);
        return null;
    }

    private String text(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual()) return value.asText();
        }
        return null;
    }

    private Integer integer(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isInt() || value.isLong() || value.isNumber()) return value.asInt();
        }
        return null;
    }

    private BigDecimal decimal(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isNumber()) return value.decimalValue();
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                try { return new BigDecimal(value.asText()); } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    private List<String> strings(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        if (nodes != null && nodes.isArray()) {
            nodes.forEach(node -> { if (node.isTextual()) values.add(node.asText()); });
        }
        return values;
    }

    private String safeMessage(Exception exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName();
    }
}
