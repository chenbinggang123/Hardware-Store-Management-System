package com.example.demo.excel.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TencentTableOcrProviderTest {
    private final TencentTableOcrProvider provider = new TencentTableOcrProvider(
            "", "", "", 5, new HttpTableOcrProvider("", "http-adapter", 5, new ObjectMapper()));

    @Test
    void convertsSdkResponseIntoStructuredCellsAndKeepsContextAsWarnings() {
        String json = """
                {
                  "Angle": 0.5,
                  "RequestId": "sdk-request-1",
                  "TableDetections": [
                    {"Cells":[{"RowTl":-1,"RowBr":-1,"ColTl":-1,"ColBr":-1,"Text":"恒迪模具五金 收款收据 NO:0002766",
                               "Polygon":[{"X":0,"Y":10}]}]},
                    {"Cells":[
                      {"RowTl":0,"RowBr":0,"ColTl":0,"ColBr":0,"Text":"项目","Confidence":100,
                       "Polygon":[{"X":1,"Y":1},{"X":9,"Y":1},{"X":9,"Y":9},{"X":1,"Y":9}]},
                      {"RowTl":0,"RowBr":0,"ColTl":1,"ColBr":1,"Text":"数量","Confidence":100},
                      {"RowTl":1,"RowBr":1,"ColTl":0,"ColBr":0,"Text":"航量3.8","Confidence":95},
                      {"RowTl":1,"RowBr":1,"ColTl":1,"ColBr":1,"Text":"10","Confidence":99}
                    ]}
                  ]
                }
                """;

        TableOcrResult result = provider.parse(json);

        assertThat(result.provider()).isEqualTo(TencentTableOcrProvider.PROVIDER);
        assertThat(result.modelVersion()).isEqualTo(TencentTableOcrProvider.MODEL_VERSION);
        assertThat(result.requestId()).isEqualTo("sdk-request-1");
        assertThat(result.detectedAngle()).isEqualByComparingTo("0.5");
        assertThat(result.tables()).hasSize(1);
        assertThat(result.tables().get(0).index()).isZero();
        assertThat(result.tables().get(0).cells()).hasSize(4);
        assertThat(result.tables().get(0).cells().get(2).rawText()).isEqualTo("航量3.8");
        assertThat(result.tables().get(0).cells().get(0).polygon()).hasSize(4);
        assertThat(result.warnings()).contains("上下文：恒迪模具五金 收款收据 NO:0002766");
        assertThat(result.rawResponse()).isEqualTo(json);
    }

    @Test
    void rejectsResponsesWithoutStructuredCells() {
        String json = """
                {"RequestId":"sdk-request-2","TableDetections":[
                  {"Cells":[{"RowTl":-1,"ColTl":-1,"Text":"只有标题"}]}
                ]}
                """;

        assertThatThrownBy(() -> provider.parse(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("结构化表格");
    }

    @Test
    void refusesToCallTheCloudWithoutCredentials() {
        assertThat(provider.isConfigured()).isFalse();
        assertThatThrownBy(() -> provider.recognize(new TableOcrRequest("a.png", "image/png", new byte[] {1})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密钥");
    }
}
