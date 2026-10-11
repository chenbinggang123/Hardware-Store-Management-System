package com.example.demo.excel.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Picks the table OCR backend for image file tasks: an explicit HTTP adapter when
 * {@code TABLE_OCR_URL} is set, otherwise the Tencent Cloud SDK when its keys exist.
 * Image tasks therefore work in the CloudBase deployment that already holds OCR keys
 * for conversation attachments, without a second OCR service.
 */
@Component
@Primary
public class ConfiguredTableOcrProvider implements TableOcrProvider {
    private final String mode;
    private final String httpUrl;
    private final HttpTableOcrProvider http;
    private final TencentTableOcrProvider tencent;

    public ConfiguredTableOcrProvider(
            @Value("${table.ocr.mode:auto}") String mode,
            @Value("${table.ocr.url:}") String httpUrl,
            HttpTableOcrProvider http,
            TencentTableOcrProvider tencent) {
        this.mode = StringUtils.hasText(mode) ? mode.trim().toLowerCase() : "auto";
        this.httpUrl = httpUrl;
        this.http = http;
        this.tencent = tencent;
    }

    public boolean isConfigured() {
        return selected() != null;
    }

    /** Name of the backend that would handle the next request, for diagnostics; null when nothing is configured. */
    public String activeBackend() {
        TableOcrProvider selected = selected();
        return selected == null ? null : selected == http ? "http-adapter" : "tencent-sdk";
    }

    @Override
    public TableOcrResult recognize(TableOcrRequest request) {
        TableOcrProvider selected = selected();
        if (selected == null) {
            throw new IllegalStateException("图片表格 OCR 尚未配置：请设置 TABLE_OCR_URL，或配置腾讯云 OCR 密钥 "
                    + "TENCENT_OCR_SECRET_ID / TENCENT_OCR_SECRET_KEY");
        }
        return selected.recognize(request);
    }

    private TableOcrProvider selected() {
        boolean httpReady = StringUtils.hasText(httpUrl);
        return switch (mode) {
            case "http", "http-adapter", "paddle" -> httpReady ? http : null;
            case "tencent", "tencent-sdk" -> tencent.isConfigured() ? tencent : null;
            case "auto" -> httpReady ? http : tencent.isConfigured() ? tencent : null;
            default -> throw new IllegalStateException("不支持的 TABLE_OCR_MODE：" + mode);
        };
    }
}
