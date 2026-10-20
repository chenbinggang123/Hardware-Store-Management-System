package com.example.demo.billing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecognizedTextSanitizerTest {
    @Test void removesDecorativeMarksButKeepsMeaningfulProductAndAmountSymbols() {
        assertThat(RecognizedTextSanitizer.clean("√ ✓示例材料甲  ☑" )).isEqualTo("示例材料甲");
        assertThat(RecognizedTextSanitizer.clean("Φ12×100 / M8-1.25（￥50.40，折扣5%）"))
                .isEqualTo("Φ12×100 / M8-1.25（￥50.40，折扣5%）");
    }
}
