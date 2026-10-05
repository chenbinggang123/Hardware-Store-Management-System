package com.example.demo.agent.service.attachment;

import java.io.InputStream;

public interface AttachmentStorage {
    void put(String objectKey, byte[] content, String contentType);
    void delete(String objectKey);
    InputStream openStream(String objectKey);
    StoredObjectMetadata stat(String objectKey);
}
