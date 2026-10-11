package com.example.demo.agent.service.attachment.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class TencentDocumentOcrClientTest {
    private final TencentDocumentOcrClient client = new TencentDocumentOcrClient(
            "", "", "", 5, new ObjectMapper());

    @Test
    void formatsTableCellsWithoutCorrectingStoreSpecificProductNames() {
        String response = """
                {
                  "Response": {
                    "Angle": 0,
                    "RequestId": "table-request-1",
                    "TableDetections": [
                      {"Cells":[{"RowTl":-1,"ColTl":-1,"Text":"恒迪模具五金 收款收据 NO:0002766",
                                  "Polygon":[{"X":0,"Y":10}]}]},
                      {"Cells":[
                        {"RowTl":0,"RowBr":0,"ColTl":0,"ColBr":0,"Text":"项目","Confidence":100},
                        {"RowTl":0,"RowBr":0,"ColTl":1,"ColBr":1,"Text":"数量","Confidence":100},
                        {"RowTl":1,"RowBr":1,"ColTl":0,"ColBr":0,"Text":"航量3.8","Confidence":95},
                        {"RowTl":1,"RowBr":1,"ColTl":1,"ColBr":1,"Text":"10","Confidence":99},
                        {"RowTl":1,"RowBr":1,"ColTl":2,"ColBr":2,"Text":"1、8","Confidence":97}
                      ]}
                    ]
                  }
                }
                """;

        DocumentOcrResult result = client.parseTableResponse(response);

        assertThat(result.provider()).isEqualTo("tencent-table-v3");
        assertThat(result.requestId()).isEqualTo("table-request-1");
        assertThat(result.text()).contains("航量3.8", "1、8", "等待用户审核");
        assertThat(result.warnings()).anyMatch(value -> value.contains("不得按常识擅自改写"));
    }

    @Test
    void returnsEmptyTableTextSoCallerCanFallbackToGeneralOcr() {
        DocumentOcrResult result = client.parseTableResponse("""
                {"Response":{"RequestId":"no-table","TableDetections":[
                  {"Cells":[{"RowTl":-1,"ColTl":-1,"Text":"普通照片文字"}]}
                ]}}
                """);

        assertThat(result.text()).isEmpty();
        assertThat(result.warnings()).anyMatch(value -> value.contains("回退"));
    }

    @Test
    void readsTencentTableV3Base64WorkbookWhenStructuredCellsAreMissing() throws Exception {
        String encoded;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("识别结果");
            sheet.createRow(0).createCell(0).setCellValue("项目");
            var item = sheet.createRow(1);
            item.createCell(0).setCellValue("航量3.8");
            item.createCell(1).setCellValue("10");
            item.createCell(2).setCellValue("1、8");
            workbook.write(output);
            encoded = Base64.getEncoder().encodeToString(output.toByteArray());
        }

        DocumentOcrResult result = client.parseTableResponse("""
                {"Response":{"RequestId":"workbook-request","Data":"%s"}}
                """.formatted(encoded));

        assertThat(result.provider()).isEqualTo("tencent-table-v3");
        assertThat(result.requestId()).isEqualTo("workbook-request");
        assertThat(result.text()).contains("[工作表] 识别结果", "航量3.8", "1、8");
    }

    @Test
    void formatsGeneralTextAndMarksLowConfidenceWithoutChangingText() {
        DocumentOcrResult result = client.parseGeneralResponse("""
                {"Response":{"RequestId":"general-request-1","TextDetections":[
                  {"DetectedText":"航量4.8","Confidence":93},
                  {"DetectedText":"9/30","Confidence":62}
                ]}}
                """);

        assertThat(result.provider()).isEqualTo("tencent-general-accurate");
        assertThat(result.text()).contains("航量4.8", "9/30 [低置信度:62]");
    }
}
