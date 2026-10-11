package com.example.demo.agent.service.attachment.ocr;

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

import java.time.Duration;
import java.util.List;

@Component
public class HttpDocumentOcrClient implements DocumentOcrClient {
    private final String url;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpDocumentOcrClient(
            @Value("${agent.attachment.ocr-url:}") String url,
            @Value("${agent.attachment.ocr-timeout-seconds:45}") int timeoutSeconds,
            ObjectMapper objectMapper) {
        this.url = url;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.min(Math.max(timeoutSeconds, 1), 10)));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(timeoutSeconds, 1)));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(url);
    }

    @Override
    public DocumentOcrResult recognize(String fileName, String mimeType, byte[] content) {
        if (!isConfigured()) throw new IllegalStateException("旧 HTTP OCR 地址尚未配置");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        });
        String response = restClient.post().uri(url)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body).retrieve().body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode text = root.path("text");
            if (!text.isTextual()) text = root.path("data").path("text");
            if (!text.isTextual() || !StringUtils.hasText(text.asText())) {
                throw new IllegalStateException("OCR 服务未返回可识别文字");
            }
            return new DocumentOcrResult("http-adapter", null, text.asText(), List.of());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("OCR 服务响应无法解析：" + safeMessage(exception), exception);
        }
    }

    private String safeMessage(Exception exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName();
    }
}
