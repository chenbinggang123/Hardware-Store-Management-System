package com.example.demo.agent;

import com.example.demo.agent.dto.AgentFileView;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOutputServiceTest {
    private ExcelTaskOutputRepository outputs;
    private AttachmentStorage storage;
    private AgentOutputService service;

    @BeforeEach
    void setUp() {
        outputs = mock(ExcelTaskOutputRepository.class);
        storage = mock(AttachmentStorage.class);
        service = new AgentOutputService(outputs, storage, new ObjectMapper());
    }

    @Test
    void filePreviewKeepsAllSheetsTotalsNotesFormattingAndPagedMerges() throws Exception {
        byte[] bytes;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var buffer = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("明细");
            sheet.createRow(0).createCell(0).setCellValue("单据标题");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 1, 0, 0));
            var row = sheet.createRow(1);
            row.createCell(1).setCellValue(7.5);
            var money = workbook.createCellStyle(); money.setDataFormat(workbook.createDataFormat().getFormat("0.00")); row.getCell(1).setCellStyle(money);
            row.createCell(2).setCellValue("金额需要核对");
            var total = sheet.createRow(2); total.createCell(0).setCellValue("合计"); total.createCell(1).setCellFormula("SUM(B2:B2)"); total.getCell(1).setCellStyle(money);
            sheet.createRow(5).createCell(0).setCellValue("末行");
            workbook.createSheet("单据信息").createRow(0).createCell(0).setCellValue("开票方");
            workbook.createSheet("核对说明").createRow(0).createCell(0).setCellValue("逐行金额缺失");
            workbook.write(buffer); bytes = buffer.toByteArray();
        }
        var file = agentFile(40L, 2L, "READY");
        when(outputs.findByIdAndOperatorId(40L, 2L)).thenReturn(Optional.of(file));
        when(storage.stat(file.getObjectKey())).thenReturn(new com.example.demo.agent.service.attachment.StoredObjectMetadata(bytes.length, "x"));
        when(storage.openStream(file.getObjectKey())).thenAnswer(ignored -> new java.io.ByteArrayInputStream(bytes));

        var page = service.previewWorkbook(40L, 2L, 0, 1, 1);
        assertThat((List<?>) page.get("sheets")).hasSize(3);
        assertThat(page).containsEntry("totalRows", 6).containsEntry("hasMore", true).containsEntry("offset", 1);
        assertThat(page.get("rows")).isEqualTo(List.of(List.of("", "7.50", "金额需要核对")));
        assertThat((List<?>) page.get("merges")).hasSize(1);
        assertThat(service.previewWorkbook(40L, 2L, 0, 2, 1).get("rows")).isEqualTo(List.of(List.of("合计", "7.50", "")));
        assertThat(service.previewWorkbook(40L, 2L, 2, 0, 100).get("rows")).isEqualTo(List.of(List.of("逐行金额缺失")));
        assertThatThrownBy(() -> service.previewWorkbook(40L, 2L, 3, 0, 100)).hasMessageContaining("工作表不存在");
        assertThatThrownBy(() -> service.previewWorkbook(40L, 2L, 0, 6, 100)).hasMessageContaining("超出");
    }

    @Test
    void filePreviewCannotReadAnotherOperatorsFile() {
        when(outputs.findByIdAndOperatorId(99L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.previewWorkbook(99L, 2L, 0, 0, 100)).isInstanceOf(IllegalArgumentException.class);
        verify(storage, never()).openStream(anyString());
    }

    @Test
    void deleteRemovesStorageObjectAndRecordForOwner() {
        ExcelTaskOutput output = agentFile(31L, 2L, "READY");
        when(outputs.findByIdAndOperatorId(31L, 2L)).thenReturn(Optional.of(output));

        service.delete(31L, 2L);

        verify(storage).delete("agent-outputs/2/202610/31-abcd.xlsx");
        verify(outputs).delete(output);
    }

    @Test
    void deleteStillRemovesRecordWhenStorageFails() {
        ExcelTaskOutput output = agentFile(32L, 2L, "READY");
        when(outputs.findByIdAndOperatorId(32L, 2L)).thenReturn(Optional.of(output));
        doThrow(new IllegalStateException("附件删除失败")).when(storage).delete(anyString());

        service.delete(32L, 2L);

        verify(outputs).delete(output);
    }

    @Test
    void deleteRejectsOtherUsersAndTaskOutputs() {
        when(outputs.findByIdAndOperatorId(33L, 9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(33L, 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");

        ExcelTaskOutput taskOutput = agentFile(34L, 2L, "READY");
        taskOutput.setSourceType("EXCEL_TASK");
        taskOutput.setTaskId(8L);
        when(outputs.findByIdAndOperatorId(34L, 2L)).thenReturn(Optional.of(taskOutput));
        assertThatThrownBy(() -> service.delete(34L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能删除小五生成的文件");
        verify(storage, never()).delete(anyString());
    }

    @Test
    void requireReadyRejectsUnfinishedFiles() {
        ExcelTaskOutput failed = agentFile(35L, 2L, "FAILED");
        when(outputs.findByIdAndOperatorId(35L, 2L)).thenReturn(Optional.of(failed));
        assertThatThrownBy(() -> service.requireReady(35L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未生成完成");

        ExcelTaskOutput ready = agentFile(36L, 2L, "READY");
        when(outputs.findByIdAndOperatorId(36L, 2L)).thenReturn(Optional.of(ready));
        assertThat(service.requireReady(36L, 2L)).isSameAs(ready);
    }

    @Test
    void downloadMarksRecordMissingWhenStorageObjectIsGone() {
        ExcelTaskOutput lost = agentFile(38L, 2L, "READY");
        when(outputs.findByIdAndOperatorId(38L, 2L)).thenReturn(Optional.of(lost));
        when(outputs.save(lost)).thenReturn(lost);
        when(storage.stat(lost.getObjectKey())).thenThrow(new IllegalStateException("NoSuchKey"));

        assertThatThrownBy(() -> service.openForDownload(38L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重新生成");
        assertThat(lost.getStatus()).isEqualTo(AgentOutputService.MISSING_STATUS);
        assertThat(lost.getErrorMessage()).contains("不在存储中");
        verify(outputs).save(lost);

        assertThatThrownBy(() -> service.requireReady(38L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重新生成");

        ExcelTaskOutput present = agentFile(39L, 2L, "READY");
        when(outputs.findByIdAndOperatorId(39L, 2L)).thenReturn(Optional.of(present));
        when(storage.stat(present.getObjectKey())).thenReturn(new com.example.demo.agent.service.attachment.StoredObjectMetadata(10, "x"));
        assertThat(service.openForDownload(39L, 2L)).isSameAs(present);
        assertThat(present.getStatus()).isEqualTo("READY");
    }

    @Test
    void readsGeneratedWorkbookBackWithoutSystemSheetsNoteColumnOrTotals() throws Exception {
        byte[] bytes;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("商品明细");
            String[] headers = {"项目", "数量", "金额", "核对说明"};
            sheet.createRow(0).createCell(0).setCellValue("客户对账单");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(5);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            org.apache.poi.ss.usermodel.Row first = sheet.createRow(6);
            first.createCell(0).setCellValue("钻头 2.2"); first.createCell(1).setCellValue(10); first.createCell(2).setCellValue(15); first.createCell(3).setCellValue("");
            org.apache.poi.ss.usermodel.Row second = sheet.createRow(7);
            second.createCell(0).setCellValue("示例管件乙"); second.createCell(1).setCellValue(28); second.createCell(2).setCellValue(504); second.createCell(3).setCellValue("金额验算不一致");
            org.apache.poi.ss.usermodel.Row total = sheet.createRow(8);
            total.createCell(0).setCellValue("合计"); total.createCell(2).setCellFormula("SUM(C7:C8)");
            org.apache.poi.ss.usermodel.Sheet info = workbook.createSheet("单据信息");
            info.createRow(0).createCell(0).setCellValue("项目");
            org.apache.poi.ss.usermodel.Row entry = info.createRow(1);
            entry.createCell(0).setCellValue("开票方"); entry.createCell(1).setCellValue("示例五金店");
            workbook.createSheet("生成说明").createRow(0).createCell(0).setCellValue("文件标题");
            workbook.write(buffer);
            bytes = buffer.toByteArray();
        }
        ExcelTaskOutput output = agentFile(40L, 2L, "READY");
        when(outputs.findByIdAndOperatorId(40L, 2L)).thenReturn(Optional.of(output));
        when(storage.stat(output.getObjectKey())).thenReturn(new com.example.demo.agent.service.attachment.StoredObjectMetadata(bytes.length, "x"));
        when(storage.openStream(output.getObjectKey())).thenReturn(new java.io.ByteArrayInputStream(bytes));

        java.util.Map<String, Object> content = service.readWorkbookContent(40L, 2L, null, null);

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> sheets = (List<java.util.Map<String, Object>>) content.get("sheets");
        assertThat(sheets).hasSize(1);
        assertThat(sheets.get(0).get("name")).isEqualTo("商品明细");
        assertThat(sheets.get(0).get("columns")).isEqualTo(List.of("项目", "数量", "金额"));
        assertThat(sheets.get(0).get("rows")).isEqualTo(List.of(List.of("钻头 2.2", "10", "15"), List.of("示例管件乙", "28", "504")));
        assertThat(sheets.get(0).get("truncated")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, String>> info = (List<java.util.Map<String, String>>) content.get("info");
        assertThat(info).hasSize(1);
        assertThat(info.get(0)).containsEntry("label", "开票方").containsEntry("value", "示例五金店");

        when(storage.openStream(output.getObjectKey())).thenReturn(new java.io.ByteArrayInputStream(bytes));
        assertThatThrownBy(() -> service.readWorkbookContent(40L, 2L, "不存在", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有名为");
    }

    @Test
    void listsOnlyAgentFilesWithDownloadPaths() {
        ExcelTaskOutput output = agentFile(37L, 2L, "READY");
        output.setSummaryJson("{\"rowCount\":7,\"mismatchCount\":1}");
        when(outputs.findByOperatorIdAndSourceTypeOrderByCreateTimeDesc(2L, AgentOutputService.AGENT_SOURCE_TYPE))
                .thenReturn(List.of(output));

        List<AgentFileView> files = service.listMine(2L);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).downloadPath()).isEqualTo("/agent/outputs/37/download");
        assertThat(files.get(0).expiresAt()).isNull();
        assertThat(files.get(0).summary()).containsEntry("rowCount", 7);
    }

    private ExcelTaskOutput agentFile(Long id, Long operatorId, String status) {
        ExcelTaskOutput output = new ExcelTaskOutput();
        output.setId(id);
        output.setOperatorId(operatorId);
        output.setSourceType(AgentOutputService.AGENT_SOURCE_TYPE);
        output.setRunId(7L);
        output.setOutputType("AGENT_XLSX");
        output.setObjectKey("agent-outputs/2/202610/" + id + "-abcd.xlsx");
        output.setOriginalName("测试输出.xlsx");
        output.setMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        output.setFileSize(1234L);
        output.setStatus(status);
        output.setCreateTime(LocalDateTime.now());
        return output;
    }
}
