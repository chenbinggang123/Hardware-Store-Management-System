package com.example.demo.agent;

import com.example.demo.agent.service.attachment.AttachmentContentParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentContentParserTest {
    private final AttachmentContentParser parser = new AttachmentContentParser("", 5, new ObjectMapper());

    @Test
    void extractsRowsFromXlsx() throws Exception {
        byte[] content;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("订单");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("商品");
            header.createCell(1).setCellValue("数量");
            var item = sheet.createRow(1);
            item.createCell(0).setCellValue("德力西空气开关 2P 32A");
            item.createCell(1).setCellValue(10);
            workbook.write(output);
            content = output.toByteArray();
        }

        var result = parser.parse("订单.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);

        assertThat(result.status()).isEqualTo("PARSED");
        assertThat(result.text()).contains("[工作表] 订单", "德力西空气开关 2P 32A", "10");
    }

    @Test
    void preservesQuotedCsvCells() {
        var result = parser.parse("订单.csv", "text/csv",
                "商品,备注,数量\n电钻,\"红色,加急\",2".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(result.status()).isEqualTo("PARSED");
        assertThat(result.text()).contains("红色,加急", "电钻");
    }

    @Test
    void imageWaitsForConfiguredOcrService() {
        var result = parser.parse("订单.png", "image/png", new byte[]{1, 2, 3});

        assertThat(result.status()).isEqualTo("WAITING_OCR");
        assertThat(result.error()).contains("PaddleOCR");
    }
}
