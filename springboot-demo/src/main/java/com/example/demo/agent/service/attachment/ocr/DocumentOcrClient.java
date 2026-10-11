package com.example.demo.agent.service.attachment.ocr;

public interface DocumentOcrClient {
    boolean isConfigured();

    DocumentOcrResult recognize(String fileName, String mimeType, byte[] content);
}
