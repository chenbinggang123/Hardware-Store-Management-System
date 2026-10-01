package com.example.demo.excel;

import com.example.demo.excel.parser.ExcelTaskParser;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelTaskParserTest {
    private final ExcelTaskParser parser = new ExcelTaskParser();

    @Test
    void preservesSheetRowNumbersAndEvaluatesFormulas() throws Exception {
        byte[] content;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("买家询价");
            var header = sheet.createRow(1);
            header.createCell(0).setCellValue("商品");
            header.createCell(1).setCellValue("数量");
            header.createCell(2).setCellValue("数量");
            var row = sheet.createRow(3);
            row.createCell(0).setCellValue("德力西空气开关");
            row.createCell(1).setCellValue(2);
            row.createCell(2).setCellFormula("B4*3");
            workbook.write(output);
            content = output.toByteArray();
        }

        var result = parser.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);

        assertThat(result.sheets()).hasSize(1);
        var sheet = result.sheets().get(0);
        assertThat(sheet.headerRowNumber()).isEqualTo(2);
        assertThat(sheet.dataStartRowNumber()).isEqualTo(4);
        assertThat(sheet.columns()).containsExactly("商品", "数量", "数量（2）");
        assertThat(sheet.rows().get(0).rowNumber()).isEqualTo(4);
        assertThat(sheet.rows().get(0).cells()).containsExactly("德力西空气开关", "2", "6");
    }

    @Test
    void readsCommonGb18030CsvAndQuotedComma() {
        byte[] content = "商品,备注,数量\r\n电钻,\"红色,加急\",2"
                .getBytes(Charset.forName("GB18030"));

        var result = parser.parse("text/csv", content);

        assertThat(result.sheets().get(0).columns()).containsExactly("商品", "备注", "数量");
        assertThat(result.sheets().get(0).rows().get(0).cells()).containsExactly("电钻", "红色,加急", "2");
    }

    @Test
    void rejectsWorkbookWithoutReadableRows() throws Exception {
        byte[] content;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("空表");
            workbook.write(output);
            content = output.toByteArray();
        }

        assertThatThrownBy(() -> parser.parse(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可读取的内容");
    }
}
