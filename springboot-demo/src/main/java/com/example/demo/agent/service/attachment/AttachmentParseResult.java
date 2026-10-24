package com.example.demo.agent.service.attachment;

/** layout 为可选的 OCR 行位置 JSON，只给开单页截图核对用，不进入模型输入。 */
public record AttachmentParseResult(String status, String text, String error, String layout) {
    public AttachmentParseResult(String status, String text, String error) {
        this(status, text, error, null);
    }

    public static AttachmentParseResult parsed(String text) {
        return new AttachmentParseResult("PARSED", text, null);
    }

    public static AttachmentParseResult parsed(String text, String layout) {
        return new AttachmentParseResult("PARSED", text, null, layout);
    }

    public static AttachmentParseResult waiting(String error) {
        return new AttachmentParseResult("WAITING_OCR", null, error);
    }

    public static AttachmentParseResult failed(String error) {
        return new AttachmentParseResult("FAILED", null, error);
    }
}
