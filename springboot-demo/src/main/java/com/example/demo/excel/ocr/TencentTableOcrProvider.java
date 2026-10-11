package com.example.demo.excel.ocr;

import com.tencentcloudapi.common.AbstractModel;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.ocr.v20181119.OcrClient;
import com.tencentcloudapi.ocr.v20181119.models.RecognizeTableAccurateOCRRequest;
import com.tencentcloudapi.ocr.v20181119.models.RecognizeTableAccurateOCRResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Table OCR straight from the Tencent Cloud SDK, so image file tasks work in production without a
 * separate HTTP adapter service. The SDK response uses the same field names the stable adapter
 * contract already accepts, so parsing is shared with {@link HttpTableOcrProvider}; this class only
 * adds the cloud call, drops free-floating context lines out of the cell grid and labels the result.
 */
@Component
public class TencentTableOcrProvider implements TableOcrProvider {
    public static final String PROVIDER = "tencent-table-v3";
    public static final String MODEL_VERSION = "RecognizeTableAccurateOCR";
    private static final int MAX_BASE64_CHARS = 10 * 1024 * 1024;

    private final String secretId;
    private final String secretKey;
    private final String region;
    private final int timeoutSeconds;
    private final HttpTableOcrProvider contractParser;

    public TencentTableOcrProvider(
            @Value("${ocr.tencent.secret-id:}") String secretId,
            @Value("${ocr.tencent.secret-key:}") String secretKey,
            @Value("${ocr.tencent.region:}") String region,
            @Value("${ocr.tencent.timeout-seconds:45}") int timeoutSeconds,
            HttpTableOcrProvider contractParser) {
        this.secretId = secretId;
        this.secretKey = secretKey;
        this.region = region == null ? "" : region.trim();
        this.timeoutSeconds = Math.max(timeoutSeconds, 1);
        this.contractParser = contractParser;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(secretId) && StringUtils.hasText(secretKey);
    }

    @Override
    public TableOcrResult recognize(TableOcrRequest request) {
        if (!isConfigured()) throw new IllegalStateException("腾讯云 OCR 密钥尚未配置");
        if (request == null || request.content() == null || request.content().length == 0) {
            throw new IllegalArgumentException("OCR 图片为空");
        }
        if (request.mimeType() == null || !request.mimeType().startsWith("image/")) {
            throw new IllegalArgumentException("腾讯云表格识别仅接收图片");
        }
        String imageBase64 = Base64.getEncoder().encodeToString(request.content());
        if (imageBase64.length() > MAX_BASE64_CHARS) {
            throw new IllegalArgumentException("图片编码后超过腾讯云 OCR 10MB 限制，请压缩或裁剪后重试");
        }
        try {
            RecognizeTableAccurateOCRRequest sdkRequest = new RecognizeTableAccurateOCRRequest();
            sdkRequest.setImageBase64(imageBase64);
            RecognizeTableAccurateOCRResponse response = client().RecognizeTableAccurateOCR(sdkRequest);
            return parse(AbstractModel.toJsonString(response));
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("腾讯云表格识别失败：" + safeMessage(exception), exception);
        }
    }

    /** Converts the raw SDK JSON into the internal contract; package-visible for contract tests. */
    TableOcrResult parse(String json) {
        TableOcrResult parsed = contractParser.parseResponse(json);
        List<String> warnings = new ArrayList<>(parsed.warnings() == null ? List.of() : parsed.warnings());
        List<TableOcrResult.Table> tables = new ArrayList<>();
        for (TableOcrResult.Table table : parsed.tables()) {
            List<TableOcrResult.Cell> cells = new ArrayList<>();
            for (TableOcrResult.Cell cell : table.cells()) {
                if (cell.rowStart() < 0 || cell.columnStart() < 0) {
                    // Tencent reports headings, remarks and signatures outside the grid with -1 positions.
                    if (StringUtils.hasText(cell.rawText())) warnings.add("上下文：" + cell.rawText());
                    continue;
                }
                cells.add(cell);
            }
            if (!cells.isEmpty()) tables.add(new TableOcrResult.Table(tables.size(), "识别表格" + (tables.size() + 1), cells));
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException("图片中没有识别到结构化表格；请拍摄包含完整表格线和表头的图片后重试");
        }
        return new TableOcrResult(PROVIDER, MODEL_VERSION, parsed.requestId(), parsed.detectedAngle(),
                parsed.imageWidth(), parsed.imageHeight(), tables, warnings, json);
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

    private String safeMessage(Exception exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName();
    }
}
