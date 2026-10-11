package com.example.demo.agent.service.attachment.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Primary
public class ConfiguredDocumentOcrClient implements DocumentOcrClient {
    private final String provider;
    private final TencentDocumentOcrClient tencent;
    private final HttpDocumentOcrClient http;

    public ConfiguredDocumentOcrClient(
            @Value("${ocr.provider:auto}") String provider,
            TencentDocumentOcrClient tencent,
            HttpDocumentOcrClient http) {
        this.provider = StringUtils.hasText(provider) ? provider.trim().toLowerCase() : "auto";
        this.tencent = tencent;
        this.http = http;
    }

    @Override
    public boolean isConfigured() {
        return selected() != null;
    }

    @Override
    public DocumentOcrResult recognize(String fileName, String mimeType, byte[] content) {
        DocumentOcrClient selected = selected();
        if (selected == null) {
            throw new IllegalStateException("OCR 尚未配置；请配置腾讯云 OCR 密钥或旧 HTTP OCR 地址");
        }
        return selected.recognize(fileName, mimeType, content);
    }

    private DocumentOcrClient selected() {
        return switch (provider) {
            case "tencent" -> tencent.isConfigured() ? tencent : null;
            case "http", "http-adapter", "paddle" -> http.isConfigured() ? http : null;
            case "auto" -> tencent.isConfigured() ? tencent : http.isConfigured() ? http : null;
            default -> throw new IllegalStateException("不支持的 OCR_PROVIDER：" + provider);
        };
    }
}
