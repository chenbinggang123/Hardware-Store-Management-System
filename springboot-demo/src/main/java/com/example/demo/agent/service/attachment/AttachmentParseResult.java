package com.example.demo.agent.service.attachment;

public record AttachmentParseResult(String status, String text, String error) {
    public static AttachmentParseResult parsed(String text) {
        return new AttachmentParseResult("PARSED", text, null);
    }

    public static AttachmentParseResult waiting(String error) {
        return new AttachmentParseResult("WAITING_OCR", null, error);
    }

    public static AttachmentParseResult failed(String error) {
        return new AttachmentParseResult("FAILED", null, error);
    }
}
