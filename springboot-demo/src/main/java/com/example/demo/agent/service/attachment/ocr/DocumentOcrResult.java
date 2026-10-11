package com.example.demo.agent.service.attachment.ocr;

import java.util.List;

public record DocumentOcrResult(String provider, String requestId, String text, List<String> warnings) {
    public DocumentOcrResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
