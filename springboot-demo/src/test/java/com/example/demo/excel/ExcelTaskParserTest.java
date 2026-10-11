package com.example.demo.excel;

import com.example.demo.excel.parser.ExcelTaskParser;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
    void detectsHeaderBelowTitleAndSupplierNotes() throws Exception {
        byte[] content;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("报价单");
            sheet.createRow(0).createCell(0).setCellValue("锐恒五金工具供货报价单");
            var notes = sheet.createRow(1);
            notes.createCell(0).setCellValue("供货方");
            notes.createCell(1).setCellValue("锐恒五金");
            notes.createCell(2).setCellValue("供货地址");
            notes.createCell(3).setCellValue("工业园");
            notes.createCell(4).setCellValue("说明");
            var header = sheet.createRow(3);
            header.createCell(0).setCellValue("商品名称");
            header.createCell(1).setCellValue("规格");
            header.createCell(2).setCellValue("单位");
            header.createCell(3).setCellValue("单价");
            var row = sheet.createRow(4);
            row.createCell(0).setCellValue("五节扣");
            row.createCell(1).setCellValue("冷");
            row.createCell(2).setCellValue("个");
            row.createCell(3).setCellValue(6);
            workbook.write(output);
            content = output.toByteArray();
        }

        var result = parser.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
        var parsed = result.sheets().get(0);

        assertThat(parsed.headerRowNumber()).isEqualTo(4);
        assertThat(parsed.dataStartRowNumber()).isEqualTo(5);
        assertThat(parsed.columns()).containsExactly("商品名称", "规格", "单位", "单价");
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).cells()).containsExactly("五节扣", "冷", "个", "6");
    }

    @Test
    void readsTablesFromDocxAsWorkbookSheets() throws Exception {
        byte[] content;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            var table = document.createTable(2, 3);
            table.getRow(0).getCell(0).setText("商品名称");
            table.getRow(0).getCell(1).setText("单位");
            table.getRow(0).getCell(2).setText("数量");
            table.getRow(1).getCell(0).setText("模具导柱");
            table.getRow(1).getCell(1).setText("支");
            table.getRow(1).getCell(2).setText("4");
            document.write(output);
            content = output.toByteArray();
        }

        var result = parser.parse("application/vnd.openxmlformats-officedocument.wordprocessingml.document", content);

        assertThat(result.sheets()).hasSize(1);
        assertThat(result.sheets().get(0).columns()).containsExactly("商品名称", "单位", "数量");
        assertThat(result.sheets().get(0).rows().get(0).cells()).containsExactly("模具导柱", "支", "4");
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
