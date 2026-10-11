package com.example.demo.excel.ocr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfiguredTableOcrProviderTest {
    private static final TableOcrResult RESULT = new TableOcrResult("x", null, null, null, null, null,
            List.of(), List.of(), null);
    private static final TableOcrRequest REQUEST = new TableOcrRequest("a.png", "image/png", new byte[] {1});

    @Test
    void prefersHttpAdapterWhenUrlIsConfigured() {
        HttpTableOcrProvider http = mock(HttpTableOcrProvider.class);
        TencentTableOcrProvider tencent = mock(TencentTableOcrProvider.class);
        when(tencent.isConfigured()).thenReturn(true);
        when(http.recognize(any())).thenReturn(RESULT);
        ConfiguredTableOcrProvider provider = new ConfiguredTableOcrProvider("auto", "http://ocr.local/table-ocr", http, tencent);

        assertThat(provider.activeBackend()).isEqualTo("http-adapter");
        assertThat(provider.recognize(REQUEST)).isSameAs(RESULT);
        verify(tencent, never()).recognize(any());
    }

    @Test
    void fallsBackToTencentSdkWhenOnlyCredentialsExist() {
        HttpTableOcrProvider http = mock(HttpTableOcrProvider.class);
        TencentTableOcrProvider tencent = mock(TencentTableOcrProvider.class);
        when(tencent.isConfigured()).thenReturn(true);
        when(tencent.recognize(any())).thenReturn(RESULT);
        ConfiguredTableOcrProvider provider = new ConfiguredTableOcrProvider("auto", "", http, tencent);

        assertThat(provider.activeBackend()).isEqualTo("tencent-sdk");
        assertThat(provider.recognize(REQUEST)).isSameAs(RESULT);
        verify(http, never()).recognize(any());
    }

    @Test
    void explainsWhatToConfigureWhenNothingIsAvailable() {
        HttpTableOcrProvider http = mock(HttpTableOcrProvider.class);
        TencentTableOcrProvider tencent = mock(TencentTableOcrProvider.class);
        ConfiguredTableOcrProvider provider = new ConfiguredTableOcrProvider("auto", "", http, tencent);

        assertThat(provider.isConfigured()).isFalse();
        assertThat(provider.activeBackend()).isNull();
        assertThatThrownBy(() -> provider.recognize(REQUEST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TABLE_OCR_URL")
                .hasMessageContaining("TENCENT_OCR_SECRET_ID");
    }

    @Test
    void explicitModeCanForceTheSdkEvenWhenUrlExists() {
        HttpTableOcrProvider http = mock(HttpTableOcrProvider.class);
        TencentTableOcrProvider tencent = mock(TencentTableOcrProvider.class);
        when(tencent.isConfigured()).thenReturn(true);
        ConfiguredTableOcrProvider provider = new ConfiguredTableOcrProvider("tencent", "http://ocr.local", http, tencent);

        assertThat(provider.activeBackend()).isEqualTo("tencent-sdk");
    }
}
