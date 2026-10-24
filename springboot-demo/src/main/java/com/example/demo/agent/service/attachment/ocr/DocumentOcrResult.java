package com.example.demo.agent.service.attachment.ocr;

import java.util.List;

/**
 * OCR 结果。layout 是可选的行位置 JSON（{"rows":{"3":[x1,y1,x2,y2]}}，坐标为原图像素），
 * 开单页据此把原图上对应的一行截出来给用户核对；不会发给模型。
 */
public record DocumentOcrResult(String provider, String requestId, String text, List<String> warnings, String layout) {
    public DocumentOcrResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public DocumentOcrResult(String provider, String requestId, String text, List<String> warnings) {
        this(provider, requestId, text, warnings, null);
    }
}
