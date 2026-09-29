package com.example.demo.agent;

import com.example.demo.agent.service.attachment.CloudBaseFileDownloader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudBaseFileDownloaderTest {
    private final CloudBaseFileDownloader downloader = new CloudBaseFileDownloader(
            "cloud1-d0gvllpf639d10665", ".tcb.qcloud.la,.myqcloud.com", 5);

    @Test
    void rejectsFileFromAnotherCloudBaseEnvironment() {
        assertThatThrownBy(() -> downloader.download(
                "cloud://another-env.bucket/order.xlsx",
                "https://example.tcb.qcloud.la/order.xlsx", 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于当前 CloudBase 环境");
    }

    @Test
    void rejectsNonHttpsDownloadUrl() {
        assertThatThrownBy(() -> downloader.download(
                "cloud://cloud1-d0gvllpf639d10665.bucket/order.xlsx",
                "http://example.tcb.qcloud.la/order.xlsx", 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("临时下载地址无效");
    }

    @Test
    void rejectsDownloadUrlOutsideCloudBase() {
        assertThatThrownBy(() -> downloader.download(
                "cloud://cloud1-d0gvllpf639d10665.bucket/order.xlsx",
                "https://example.com/order.xlsx", 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许从该地址读取附件");
    }
}
