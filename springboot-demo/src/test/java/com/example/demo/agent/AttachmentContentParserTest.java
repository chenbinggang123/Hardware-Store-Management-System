package com.example.demo.agent;

import com.example.demo.agent.service.attachment.AttachmentContentParser;
import com.example.demo.agent.service.attachment.ocr.DocumentOcrClient;
import com.example.demo.agent.service.attachment.ocr.DocumentOcrResult;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentContentParserTest {
    private final AttachmentContentParser parser = new AttachmentContentParser(new DocumentOcrClient() {
        @Override public boolean isConfigured() { return false; }
        @Override public DocumentOcrResult recognize(String fileName, String mimeType, byte[] content) {
            throw new IllegalStateException("不应调用");
        }
    }, "ffmpeg", 60);

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
    void extractsParagraphsAndTablesFromDocx() throws Exception {
        byte[] content;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("宏达装修队采购清单");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("商品");
            table.getRow(0).getCell(1).setText("数量");
            table.getRow(1).getCell(0).setText("空气开关");
            table.getRow(1).getCell(1).setText("10");
            document.write(output);
            content = output.toByteArray();
        }

        var result = parser.parse("采购清单.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", content);

        assertThat(result.status()).isEqualTo("PARSED");
        assertThat(result.text()).contains("宏达装修队采购清单", "[表格 1]", "空气开关", "10");
    }

    @Test
    void imageWaitsForConfiguredOcrService() {
        var result = parser.parse("订单.png", "image/png", new byte[]{1, 2, 3});

        assertThat(result.status()).isEqualTo("WAITING_OCR");
        assertThat(result.error()).contains("OCR");
    }

    @Test
    void imageKeepsRawOcrTextAndShowsReviewWarning() {
        var configuredParser = new AttachmentContentParser(new DocumentOcrClient() {
            @Override public boolean isConfigured() { return true; }
            @Override public DocumentOcrResult recognize(String fileName, String mimeType, byte[] content) {
                return new DocumentOcrResult("tencent-table-v3", "request-1", "商品：航量3.8",
                        java.util.List.of("商品名称必须由用户审核"));
            }
        }, "ffmpeg", 60);

        var result = configuredParser.parse("订单.png", "image/png", new byte[]{1, 2, 3});

        assertThat(result.status()).isEqualTo("PARSED");
        assertThat(result.text()).contains("航量3.8", "tencent-table-v3", "request-1", "[审核提醒] 商品名称必须由用户审核");
    }

    @Test
    void videoWaitsForConfiguredOcrServiceWithoutStartingFfmpeg() {
        var result = parser.parse("厂商订单.mp4", "video/mp4", new byte[]{0, 0, 0, 20});

        assertThat(result.status()).isEqualTo("WAITING_OCR");
        assertThat(result.error()).contains("视频已保存", "OCR");
    }
}
