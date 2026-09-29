package com.example.demo.agent.service.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class AttachmentContentParser {
    private static final int MAX_ROWS = 2_000;
    private static final int MAX_COLUMNS = 80;
    private static final int MAX_EXTRACTED_CHARS = 60_000;

    private final String ocrUrl;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AttachmentContentParser(
            @Value("${agent.attachment.ocr-url:}") String ocrUrl,
            @Value("${agent.attachment.ocr-timeout-seconds:45}") int timeoutSeconds,
            ObjectMapper objectMapper) {
        this.ocrUrl = ocrUrl;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)));
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public AttachmentParseResult parse(String fileName, String mimeType, byte[] content) {
        try {
            if (isSpreadsheet(mimeType)) {
                return AttachmentParseResult.parsed(parseWorkbook(content));
            }
            if ("text/csv".equals(mimeType)) {
                return AttachmentParseResult.parsed(parseCsv(content));
            }
            if (mimeType.startsWith("image/")) {
                return parseImage(fileName, mimeType, content);
            }
            return AttachmentParseResult.failed("暂不支持该文件类型");
        } catch (Exception exception) {
            return AttachmentParseResult.failed("文件解析失败：" + safeMessage(exception));
        }
    }

    private String parseWorkbook(byte[] content) throws Exception {
        StringBuilder output = new StringBuilder();
        DataFormatter formatter = new DataFormatter(java.util.Locale.CHINA);
        int rowCount = 0;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (Sheet sheet : workbook) {
                append(output, "[工作表] " + sheet.getSheetName() + "\n");
                for (Row row : sheet) {
                    if (++rowCount > MAX_ROWS) {
                        append(output, "[内容已截断：最多读取 " + MAX_ROWS + " 行]\n");
                        return output.toString();
                    }
                    List<String> cells = new ArrayList<>();
                    int last = Math.min(Math.max(row.getLastCellNum(), 0), MAX_COLUMNS);
                    for (int index = 0; index < last; index++) {
                        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                    }
                    while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                        cells.remove(cells.size() - 1);
                    }
                    if (!cells.isEmpty()) {
                        append(output, "第" + (row.getRowNum() + 1) + "行\t" + String.join("\t", cells) + "\n");
                    }
                }
            }
        }
        if (output.isEmpty()) {
            throw new IllegalArgumentException("表格中没有可读取的内容");
        }
        return output.toString();
    }

    private String parseCsv(byte[] content) {
        String source = new String(content, StandardCharsets.UTF_8);
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        List<List<String>> rows = csvRows(source);
        StringBuilder output = new StringBuilder("[CSV 文件]\n");
        for (int index = 0; index < Math.min(rows.size(), MAX_ROWS); index++) {
            List<String> row = rows.get(index);
            append(output, "第" + (index + 1) + "行\t"
                    + String.join("\t", row.subList(0, Math.min(row.size(), MAX_COLUMNS))) + "\n");
        }
        if (rows.size() > MAX_ROWS) append(output, "[内容已截断：最多读取 " + MAX_ROWS + " 行]\n");
        return output.toString();
    }

    private List<List<String>> csvRows(String source) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (value == '"') {
                if (quoted && i + 1 < source.length() && source.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (value == ',' && !quoted) {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if ((value == '\n' || value == '\r') && !quoted) {
                if (value == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim());
                cell.setLength(0);
                if (row.stream().anyMatch(StringUtils::hasText)) rows.add(row);
                row = new ArrayList<>();
            } else cell.append(value);
        }
        row.add(cell.toString().trim());
        if (row.stream().anyMatch(StringUtils::hasText)) rows.add(row);
        return rows;
    }

    private AttachmentParseResult parseImage(String fileName, String mimeType, byte[] content) throws Exception {
        if (!StringUtils.hasText(ocrUrl)) {
            return AttachmentParseResult.waiting("图片已保存；部署并配置 PaddleOCR 服务后即可识别");
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        });
        String response = restClient.post().uri(ocrUrl)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body).retrieve().body(String.class);
        JsonNode root = objectMapper.readTree(response);
        JsonNode text = root.path("text");
        if (!text.isTextual()) text = root.path("data").path("text");
        if (!text.isTextual() || !StringUtils.hasText(text.asText())) {
            throw new IllegalStateException("OCR 服务未返回可识别文字");
        }
        return AttachmentParseResult.parsed(limit(text.asText()));
    }

    private boolean isSpreadsheet(String mimeType) {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mimeType)
                || "application/vnd.ms-excel".equals(mimeType);
    }

    private void append(StringBuilder target, String value) {
        if (target.length() < MAX_EXTRACTED_CHARS) target.append(value);
    }

    private String limit(String value) {
        return value.length() <= MAX_EXTRACTED_CHARS ? value : value.substring(0, MAX_EXTRACTED_CHARS);
    }

    private String safeMessage(Exception exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName();
    }
}
