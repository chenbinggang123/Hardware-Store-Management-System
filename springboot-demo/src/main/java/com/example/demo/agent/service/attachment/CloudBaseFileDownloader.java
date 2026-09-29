package com.example.demo.agent.service.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class CloudBaseFileDownloader {
    private final HttpClient client;
    private final String environmentId;
    private final List<String> allowedHostSuffixes;
    private final Duration requestTimeout;

    public CloudBaseFileDownloader(
            @Value("${agent.attachment.cloud-env-id:}") String environmentId,
            @Value("${agent.attachment.cloud-download-host-suffixes:.tcb.qcloud.la,.myqcloud.com}") String hostSuffixes,
            @Value("${agent.attachment.cloud-download-timeout-seconds:20}") int timeoutSeconds) {
        this.environmentId = environmentId;
        this.allowedHostSuffixes = Arrays.stream(hostSuffixes.split(","))
                .map(String::trim).filter(StringUtils::hasText).map(value -> value.toLowerCase(Locale.ROOT)).toList();
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public byte[] download(String cloudFileId, String downloadUrl, long maxSize) {
        validateFileId(cloudFileId);
        URI uri = validateUrl(downloadUrl);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("CloudBase 文件读取失败（HTTP " + response.statusCode() + "）");
            }
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declaredLength > maxSize) throw tooLarge(maxSize);
            try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                long total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxSize) throw tooLarge(maxSize);
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法从 CloudBase 云存储读取附件", exception);
        }
    }

    private void validateFileId(String cloudFileId) {
        if (!StringUtils.hasText(cloudFileId) || !cloudFileId.startsWith("cloud://")) {
            throw new IllegalArgumentException("CloudBase fileID 无效");
        }
        if (StringUtils.hasText(environmentId) && !cloudFileId.startsWith("cloud://" + environmentId + ".")) {
            throw new IllegalArgumentException("附件不属于当前 CloudBase 环境");
        }
    }

    private URI validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(host)
                    || uri.getUserInfo() != null || uri.getPort() != -1) {
                throw new IllegalArgumentException("CloudBase 临时下载地址无效");
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean allowed = allowedHostSuffixes.stream().anyMatch(suffix -> normalizedHost.endsWith(suffix));
            if (!allowed) throw new IllegalArgumentException("不允许从该地址读取附件");
            return uri;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("CloudBase 临时下载地址无效", exception);
        }
    }

    private IllegalArgumentException tooLarge(long maxSize) {
        return new IllegalArgumentException("单个附件不能超过 " + maxSize / 1024 / 1024 + "MB");
    }
}
