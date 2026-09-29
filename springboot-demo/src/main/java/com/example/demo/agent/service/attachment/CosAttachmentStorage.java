package com.example.demo.agent.service.attachment;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;

@Component
@ConditionalOnProperty(name = "agent.attachment.storage", havingValue = "cos")
public class CosAttachmentStorage implements AttachmentStorage {
    private final COSClient client;
    private final String bucket;

    public CosAttachmentStorage(
            @Value("${agent.attachment.cos.secret-id:}") String secretId,
            @Value("${agent.attachment.cos.secret-key:}") String secretKey,
            @Value("${agent.attachment.cos.region:}") String region,
            @Value("${agent.attachment.cos.bucket:}") String bucket) {
        if (!StringUtils.hasText(secretId) || !StringUtils.hasText(secretKey)
                || !StringUtils.hasText(region) || !StringUtils.hasText(bucket)) {
            throw new IllegalStateException("COS 附件存储配置不完整");
        }
        COSCredentials credentials = new BasicCOSCredentials(secretId, secretKey);
        this.client = new COSClient(credentials, new ClientConfig(new Region(region)));
        this.bucket = bucket;
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setContentType(contentType);
        client.putObject(bucket, objectKey, new ByteArrayInputStream(content), metadata);
    }

    @Override
    public void delete(String objectKey) {
        client.deleteObject(bucket, objectKey);
    }

    @PreDestroy
    public void close() {
        client.shutdown();
    }
}
