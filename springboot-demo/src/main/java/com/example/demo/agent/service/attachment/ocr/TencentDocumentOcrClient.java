package com.example.demo.agent.service.attachment.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentcloudapi.common.AbstractModel;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.ocr.v20181119.OcrClient;
import com.tencentcloudapi.ocr.v20181119.models.GeneralAccurateOCRRequest;
import com.tencentcloudapi.ocr.v20181119.models.GeneralAccurateOCRResponse;
import com.tencentcloudapi.ocr.v20181119.models.RecognizeTableAccurateOCRRequest;
import com.tencentcloudapi.ocr.v20181119.models.RecognizeTableAccurateOCRResponse;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class TencentDocumentOcrClient implements DocumentOcrClient {
    private static final int MAX_BASE64_BYTES = 10 * 1024 * 1024;
    private static final int MAX_TABLE_ROWS = 2_000;
    private static final int MAX_TABLE_COLUMNS = 80;

    private final String secretId;
    private final String secretKey;
    private final String region;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper;

    public TencentDocumentOcrClient(
            @Value("${ocr.tencent.secret-id:}") String secretId,
            @Value("${ocr.tencent.secret-key:}") String secretKey,
            @Value("${ocr.tencent.region:}") String region,
            @Value("${ocr.tencent.timeout-seconds:45}") int timeoutSeconds,
            ObjectMapper objectMapper) {
        this.secretId = secretId;
        this.secretKey = secretKey;
        this.region = region == null ? "" : region.trim();
        this.timeoutSeconds = Math.max(timeoutSeconds, 1);
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(secretId) && StringUtils.hasText(secretKey);
    }

    @Override
    public DocumentOcrResult recognize(String fileName, String mimeType, byte[] content) {
        if (!isConfigured()) throw new IllegalStateException("腾讯云 OCR 密钥尚未配置");
        if (content == null || content.length == 0) throw new IllegalArgumentException("OCR 图片为空");
        if (mimeType == null || !mimeType.startsWith("image/")) {
            throw new IllegalArgumentException("腾讯云 OCR 当前仅接收图片帧");
        }
        String imageBase64 = Base64.getEncoder().encodeToString(content);
        if (imageBase64.length() > MAX_BASE64_BYTES) {
            throw new IllegalArgumentException("图片编码后超过腾讯云 OCR 10MB 限制，请压缩或裁剪后重试");
        }

        Exception tableFailure = null;
        try {
            RecognizeTableAccurateOCRRequest request = new RecognizeTableAccurateOCRRequest();
            request.setImageBase64(imageBase64);
            RecognizeTableAccurateOCRResponse response = client().RecognizeTableAccurateOCR(request);
            DocumentOcrResult result = parseTableResponse(AbstractModel.toJsonString(response));
            if (StringUtils.hasText(result.text())) return result;
        } catch (Exception exception) {
            tableFailure = exception;
        }

        try {
            GeneralAccurateOCRRequest request = new GeneralAccurateOCRRequest();
            request.setImageBase64(imageBase64);
            GeneralAccurateOCRResponse response = client().GeneralAccurateOCR(request);
            return parseGeneralResponse(AbstractModel.toJsonString(response));
        } catch (Exception exception) {
            String message = "腾讯云 OCR 识别失败：" + safeMessage(exception);
            if (tableFailure != null) message += "；表格识别：" + safeMessage(tableFailure);
            throw new IllegalStateException(message, exception);
        }
    }

    DocumentOcrResult parseTableResponse(String response) {
        try {
            JsonNode payload = payload(response);
            List<JsonNode> contexts = new ArrayList<>();
            List<JsonNode> structuredTables = new ArrayList<>();
            JsonNode detections = payload.path("TableDetections");
            if (detections.isArray()) {
                detections.forEach(table -> {
                    JsonNode cells = table.path("Cells");
                    if (!cells.isArray()) return;
                    boolean structured = false;
                    for (JsonNode cell : cells) {
                        if (cell.path("RowTl").asInt(-1) >= 0 && cell.path("ColTl").asInt(-1) >= 0) {
                            structured = true;
                            break;
                        }
                    }
                    if (structured) structuredTables.add(table);
                    else cells.forEach(cell -> { if (hasText(cell.path("Text"))) contexts.add(cell); });
                });
            }
            if (structuredTables.isEmpty()) {
                String workbookText = parseEncodedWorkbook(text(payload, "Data"));
                if (StringUtils.hasText(workbookText)) {
                    return new DocumentOcrResult("tencent-table-v3", text(payload, "RequestId"), workbookText,
                            reviewWarnings());
                }
                return new DocumentOcrResult("tencent-table-v3", text(payload, "RequestId"), "",
                        List.of("没有检测到有效表格，已回退通用高精度识别"));
            }

            StringBuilder output = new StringBuilder("[腾讯云表格识别 V3：OCR原文，等待用户审核]\n");
            contexts.stream().sorted(Comparator.comparingInt(this::topY)).forEach(cell ->
                    output.append("[上下文] ").append(clean(cell.path("Text").asText())).append('\n'));
            int tableIndex = 0;
            for (JsonNode table : structuredTables) {
                output.append("[表格 ").append(++tableIndex).append("]\n");
                Map<Integer, List<JsonNode>> rows = new TreeMap<>();
                for (JsonNode cell : table.path("Cells")) {
                    int row = cell.path("RowTl").asInt(-1);
                    int column = cell.path("ColTl").asInt(-1);
                    if (row < 0 || column < 0 || !hasText(cell.path("Text"))) continue;
                    rows.computeIfAbsent(row, ignored -> new ArrayList<>()).add(cell);
                }
                rows.forEach((row, cells) -> {
                    cells.sort(Comparator.comparingInt(cell -> cell.path("ColTl").asInt()));
                    output.append("第").append(row + 1).append("行\t");
                    List<String> values = new ArrayList<>();
                    for (JsonNode cell : cells) {
                        values.add("列" + (cell.path("ColTl").asInt() + 1) + "=" + clean(cell.path("Text").asText()));
                    }
                    output.append(String.join("\t", values)).append('\n');
                });
            }
            return new DocumentOcrResult("tencent-table-v3", text(payload, "RequestId"), output.toString().trim(),
                    reviewWarnings());
        } catch (Exception exception) {
            throw new IllegalStateException("腾讯云表格 OCR 响应无法解析：" + safeMessage(exception), exception);
        }
    }

    DocumentOcrResult parseGeneralResponse(String response) {
        try {
            JsonNode payload = payload(response);
            JsonNode detections = payload.path("TextDetections");
            if (!detections.isArray()) throw new IllegalStateException("高精度 OCR 未返回文字行");
            StringBuilder output = new StringBuilder("[腾讯云通用文字识别（高精度版）：OCR原文，等待用户审核]\n");
            for (JsonNode detection : detections) {
                String detected = text(detection, "DetectedText");
                if (!StringUtils.hasText(detected)) continue;
                output.append(clean(detected));
                int confidence = detection.path("Confidence").asInt(100);
                if (confidence < 85) output.append(" [低置信度:").append(confidence).append("]");
                output.append('\n');
            }
            if (output.toString().lines().count() <= 1) throw new IllegalStateException("高精度 OCR 没有识别到文字");
            return new DocumentOcrResult("tencent-general-accurate", text(payload, "RequestId"), output.toString().trim(),
                    reviewWarnings());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("腾讯云高精度 OCR 响应无法解析：" + safeMessage(exception), exception);
        }
    }

    private OcrClient client() {
        Credential credential = new Credential(secretId, secretKey);
        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint("ocr.tencentcloudapi.com");
        httpProfile.setConnTimeout(Math.min(timeoutSeconds, 10));
        httpProfile.setReadTimeout(timeoutSeconds);
        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        return new OcrClient(credential, region, clientProfile);
    }

    private String parseEncodedWorkbook(String encoded) {
        if (!StringUtils.hasText(encoded)) return "";
        try {
            byte[] workbookBytes = Base64.getDecoder().decode(encoded);
            StringBuilder output = new StringBuilder("[腾讯云表格识别 V3：OCR原文，等待用户审核]\n");
            DataFormatter formatter = new DataFormatter(java.util.Locale.CHINA);
            int rowCount = 0;
            try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
                for (var sheet : workbook) {
                    output.append("[工作表] ").append(sheet.getSheetName()).append('\n');
                    for (var row : sheet) {
                        if (++rowCount > MAX_TABLE_ROWS) {
                            output.append("[内容已截断：最多读取 ").append(MAX_TABLE_ROWS).append(" 行]\n");
                            return output.toString().trim();
                        }
                        int lastColumn = Math.min(Math.max(row.getLastCellNum(), 0), MAX_TABLE_COLUMNS);
                        List<String> values = new ArrayList<>();
                        for (int column = 0; column < lastColumn; column++) {
                            var cell = row.getCell(column, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                            values.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                        }
                        while (!values.isEmpty() && values.get(values.size() - 1).isEmpty()) values.remove(values.size() - 1);
                        if (!values.isEmpty()) {
                            output.append("第").append(row.getRowNum() + 1).append("行\t")
                                    .append(String.join("\t", values)).append('\n');
                        }
                    }
                }
            }
            return output.toString().lines().count() > 1 ? output.toString().trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private JsonNode payload(String response) throws Exception {
        if (!StringUtils.hasText(response)) throw new IllegalStateException("腾讯云 OCR 返回空响应");
        JsonNode root = objectMapper.readTree(response);
        JsonNode payload = root.path("Response").isObject() ? root.path("Response") : root;
        if (payload.path("Error").isObject()) {
            String code = text(payload.path("Error"), "Code");
            String message = text(payload.path("Error"), "Message");
            throw new IllegalStateException((StringUtils.hasText(code) ? code + "：" : "") + message);
        }
        return payload;
    }

    private List<String> reviewWarnings() {
        return List.of(
                "OCR结果仅生成待审核草稿，商品名称和单位必须保留原文，不得按常识擅自改写",
                "数量、单价、行金额和合计需要显示给用户核对；验算差异只能提示，不能静默覆盖OCR原值");
    }

    private int topY(JsonNode cell) {
        JsonNode polygon = cell.path("Polygon");
        return polygon.isArray() && !polygon.isEmpty() ? polygon.get(0).path("Y").asInt() : Integer.MAX_VALUE;
    }

    private boolean hasText(JsonNode value) {
        return value.isTextual() && StringUtils.hasText(value.asText());
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private String safeMessage(Exception exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName();
    }
}
