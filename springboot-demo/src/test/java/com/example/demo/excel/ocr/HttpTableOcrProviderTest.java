package com.example.demo.excel.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTableOcrProviderTest {
    private final HttpTableOcrProvider provider = new HttpTableOcrProvider(
            "", "test-adapter", 5, new ObjectMapper());

    @Test
    void parsesStableAdapterContract() {
        String json = """
                {
                  "data": {
                    "provider": "tencent-table-v3",
                    "requestId": "req-9",
                    "angle": 1.25,
                    "imageWidth": 1080,
                    "imageHeight": 1920,
                    "tables": [{
                      "index": 0,
                      "name": "价格表",
                      "cells": [{
                        "rowStart": 0,
                        "rowEnd": 0,
                        "columnStart": 1,
                        "columnEnd": 1,
                        "rawText": "6·5",
                        "confidence": 0.91,
                        "polygon": [[10, 20], [30, 20], [30, 40], [10, 40]]
                      }]
                    }]
                  }
                }
                """;

        var result = provider.parseResponse(json);

        assertThat(result.provider()).isEqualTo("tencent-table-v3");
        assertThat(result.requestId()).isEqualTo("req-9");
        assertThat(result.tables().get(0).cells().get(0).rawText()).isEqualTo("6·5");
        assertThat(result.tables().get(0).cells().get(0).polygon()).hasSize(4);
    }

    @Test
    void acceptsTencentStyleCellNames() {
        String json = """
                {
                  "Response": {
                    "RequestId": "tencent-request",
                    "TableDetections": [{
                      "Cells": [{"RowTl": 0, "RowBr": 0, "ColTl": 0, "ColBr": 0,
                                 "Text": "名称", "Confidence": 99.8}]
                    }]
                  }
                }
                """;

        var result = provider.parseResponse(json);

        assertThat(result.tables()).hasSize(1);
        assertThat(result.tables().get(0).cells().get(0).rawText()).isEqualTo("名称");
    }

    @Test
    void rejectsTextOnlyOcrResponse() {
        assertThatThrownBy(() -> provider.parseResponse("{\"text\":\"名称 价格\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("表格单元格");
    }
}
