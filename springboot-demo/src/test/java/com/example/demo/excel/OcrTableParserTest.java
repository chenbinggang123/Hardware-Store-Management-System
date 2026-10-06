package com.example.demo.excel;

import com.example.demo.excel.ocr.TableOcrResult;
import com.example.demo.excel.parser.OcrTableParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OcrTableParserTest {
    @Test
    void restoresCellsIntoWorkbookRowsWithoutFlatteningTheTable() {
        var result = new TableOcrResult("test", "v1", "request-1", BigDecimal.ZERO,
                1200, 800, List.of(new TableOcrResult.Table(0, "价格表", List.of(
                cell(0, 0, "名称"), cell(0, 1, "规格"), cell(0, 2, "价格"),
                cell(1, 0, "五节扣"), cell(1, 1, "冷"), cell(1, 2, "6"),
                cell(2, 0, "六节扣"), cell(2, 1, "热"), cell(2, 2, "6·5")
        ))), List.of(), "{}");
        var parser = new OcrTableParser(request -> result);

        var workbook = parser.parse("价格表.jpg", "image/jpeg", new byte[]{1, 2, 3});

        assertThat(workbook.sheets()).hasSize(1);
        assertThat(workbook.sheets().get(0).columns()).containsExactly("名称", "规格", "价格");
        assertThat(workbook.sheets().get(0).rows()).hasSize(2);
        assertThat(workbook.sheets().get(0).rows().get(0).cells()).containsExactly("五节扣", "冷", "6");
        assertThat(workbook.sheets().get(0).rows().get(1).cells()).containsExactly("六节扣", "热", "6·5");
    }

    @Test
    void keepsBlankColumnsSoLaterFieldMappingRemainsStable() {
        var result = new TableOcrResult("test", null, null, null, null, null,
                List.of(new TableOcrResult.Table(0, "表", List.of(
                        cell(0, 0, "名称"), cell(0, 2, "价格"),
                        cell(1, 0, "料咀 EVA"), cell(1, 2, "75")
                ))), List.of(), "{}");

        var workbook = new OcrTableParser(request -> result)
                .parse("表.png", "image/png", new byte[]{1});

        assertThat(workbook.sheets().get(0).columns()).containsExactly("名称", "列B", "价格");
        assertThat(workbook.sheets().get(0).rows().get(0).cells()).containsExactly("料咀 EVA", "", "75");
    }

    private TableOcrResult.Cell cell(int row, int column, String text) {
        return new TableOcrResult.Cell(row, row, column, column, text,
                new BigDecimal("0.98"), List.of());
    }
}
