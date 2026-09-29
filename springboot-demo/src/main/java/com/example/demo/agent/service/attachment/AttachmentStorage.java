package com.example.demo.agent.service.attachment;

public interface AttachmentStorage {
    void put(String objectKey, byte[] content, String contentType);
    void delete(String objectKey);
}
