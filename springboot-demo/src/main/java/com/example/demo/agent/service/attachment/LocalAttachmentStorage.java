package com.example.demo.agent.service.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@ConditionalOnProperty(name = "agent.attachment.storage", havingValue = "local", matchIfMissing = true)
public class LocalAttachmentStorage implements AttachmentStorage {
    private final Path root;

    public LocalAttachmentStorage(@Value("${agent.attachment.local-directory:./data/agent-attachments}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        Path target = target(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException exception) {
            throw new IllegalStateException("附件保存失败", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(target(objectKey));
        } catch (IOException exception) {
            throw new IllegalStateException("附件删除失败", exception);
        }
    }

    @Override
    public InputStream openStream(String objectKey) {
        try {
            return Files.newInputStream(target(objectKey));
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件不存在或无法读取", exception);
        }
    }

    @Override
    public StoredObjectMetadata stat(String objectKey) {
        Path path = target(objectKey);
        try {
            return new StoredObjectMetadata(Files.size(path), Files.probeContentType(path));
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件不存在或无法读取", exception);
        }
    }

    private Path target(String objectKey) {
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("非法附件路径");
        return target;
    }
}
