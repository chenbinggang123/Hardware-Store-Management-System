package com.example.demo.agent.workbook;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkbookServiceTest {
    private ExcelTaskOutputRepository outputs;
    private AgentAttachmentRepository attachments;
    private AttachmentStorage storage;
    private AgentWorkbookService service;
    private final AtomicReference<ExcelTaskOutput> stored = new AtomicReference<>();
    private final AtomicReference<byte[]> bytes = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        outputs = mock(ExcelTaskOutputRepository.class);
        attachments = mock(AgentAttachmentRepository.class);
        storage = mock(AttachmentStorage.class);
        service = new AgentWorkbookService(outputs, attachments, storage, new ObjectMapper());
        when(outputs.findByOperatorIdAndIdempotencyKey(eq(2L), anyString())).thenReturn(Optional.empty());
        when(outputs.saveAndFlush(any())).thenAnswer(invocation -> {
            ExcelTaskOutput value = invocation.getArgument(0);
            value.setId(90L);
            stored.set(value);
            return value;
        });
        when(outputs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> { bytes.set(invocation.getArgument(1)); return null; })
                .when(storage).put(anyString(), any(byte[].class), anyString());
    }

    @Test
    void generatesVerifiedWorkbookWithTotalsAndProvenance() throws Exception {
        AgentAttachment attachment = new AgentAttachment();
        attachment.setId(5L);
        attachment.setOperatorId(2L);
        attachment.setConversationId(11L);
        attachment.setOriginalName("收据.png");
        attachment.setSha256("abc123");
        attachment.setParseStatus("PARSED");
        attachment.setExtractedText("第1行 钻头 2.2 支 10 1.5 15\n第7行 气管 10 米 28 1.8 504");
        when(attachments.findByIdInAndOperatorId(List.of(5L), 2L)).thenReturn(List.of(attachment));

        Map<String, Object> result = service.generate(7L, 2L, receiptSpec(5L, true));

        assertThat(result.get("status")).isEqualTo("READY");
        assertThat(result.get("outputId")).isEqualTo(90L);
        assertThat(result.get("downloadPath")).isEqualTo("/agent/outputs/90/download");
        assertThat(result.get("mismatchCount")).isEqualTo(1);
        assertThat(result.get("rowCount")).isEqualTo(3);

        ExcelTaskOutput saved = stored.get();
        assertThat(saved.getSourceType()).isEqualTo(AgentOutputService.AGENT_SOURCE_TYPE);
        assertThat(saved.getTaskId()).isNull();
        assertThat(saved.getExpiresAt()).isNull();
        assertThat(saved.getRunId()).isEqualTo(7L);
        assertThat(saved.getSourceId()).isEqualTo(5L);
        assertThat(saved.getIdempotencyKey()).startsWith("agent-xlsx-7-");
        assertThat(saved.getOriginalName()).startsWith("恒迪模具五金收款收据0002766-").endsWith(".xlsx");
        assertThat(saved.getStatus()).isEqualTo("READY");
        assertThat(saved.getSummaryJson()).contains("\"mismatchCount\":1");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.get()))) {
            Sheet sheet = workbook.getSheet("商品明细");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(6).getStringCellValue()).isEqualTo(AgentWorkbookService.NOTE_COLUMN);

            Row first = sheet.getRow(1);
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("钻头 2.2");
            assertThat(first.getCell(3).getNumericCellValue()).isEqualTo(10d);
            assertThat(first.getCell(5).getNumericCellValue()).isEqualTo(15d);
            assertThat(first.getCell(6).getStringCellValue()).isEmpty();

            Row second = sheet.getRow(2);
            assertThat(second.getCell(5).getNumericCellValue()).isEqualTo(504d);
            assertThat(second.getCell(5).getCellStyle().getFillForegroundColor())
                    .isEqualTo(IndexedColors.LIGHT_YELLOW.getIndex());
            assertThat(second.getCell(6).getStringCellValue()).contains("28×1.8=50.40").contains("不一致");

            Row third = sheet.getRow(3);
            assertThat(third.getCell(1).getStringCellValue()).isEqualTo("'=危险公式");
            assertThat(third.getCell(5).getNumericCellValue()).isEqualTo(80d);
            assertThat(third.getCell(6).getStringCellValue()).contains("补算为 80.00");

            Row total = sheet.getRow(4);
            assertThat(total.getCell(0).getStringCellValue()).isEqualTo("合计");
            Cell sum = total.getCell(5);
            assertThat(sum.getCellFormula()).isEqualTo("SUM(F2:F4)");
            assertThat(sum.getNumericCellValue()).isEqualTo(599d);

            Sheet info = workbook.getSheet(AgentWorkbookService.INFO_SHEET);
            assertThat(info.getRow(1).getCell(0).getStringCellValue()).isEqualTo("开票方");
            assertThat(info.getRow(2).getCell(1).getStringCellValue()).isEqualTo("0002766");

            Sheet source = workbook.getSheet(AgentWorkbookService.SOURCE_SHEET);
            assertThat(source.getRow(1).getCell(0).getStringCellValue()).startsWith("第1行");

            Sheet about = workbook.getSheet(AgentWorkbookService.ABOUT_SHEET);
            List<String> labels = new ArrayList<>();
            for (Row row : about) labels.add(row.getCell(0).getStringCellValue());
            assertThat(labels).contains("来源附件", "附件 SHA-256", "金额验算差异", "声明");
        }
    }

    @Test
    void keepsTextColumnsAsTextAndRejectsEmptyTables() {
        AgentWorkbookSpec spec = new AgentWorkbookSpec();
        spec.setTitle("价格表");
        AgentWorkbookSpec.SheetSpec sheet = new AgentWorkbookSpec.SheetSpec();
        sheet.setName("清单");
        sheet.setColumns(List.of(new AgentWorkbookSpec.ColumnSpec("单号", "TEXT", null),
                new AgentWorkbookSpec.ColumnSpec("库存", null, null)));
        sheet.setRows(List.of(Arrays.asList("0002766", "12件"), Arrays.asList(null, "")));
        spec.setSheets(List.of(sheet));

        Map<String, Object> preview = service.preview(7L, 2L, spec);

        assertThat(preview.get("kind")).isEqualTo("EXCEL_WORKBOOK");
        assertThat(preview.get("rowCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sheets = (List<Map<String, Object>>) preview.get("sheets");
        assertThat(sheets.get(0).get("sampleRows")).isEqualTo(List.of(List.of("0002766", "12件")));
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) preview.get("warnings");
        assertThat(warnings).anyMatch(item -> item.contains("不是数字"));

        AgentWorkbookSpec empty = new AgentWorkbookSpec();
        empty.setTitle("空表");
        AgentWorkbookSpec.SheetSpec blank = new AgentWorkbookSpec.SheetSpec();
        blank.setName("空");
        blank.setColumns(List.of(new AgentWorkbookSpec.ColumnSpec("列", null, null)));
        blank.setRows(List.of());
        empty.setSheets(List.of(blank));
        assertThatThrownBy(() -> service.preview(7L, 2L, empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可写入的数据行");
    }

    @Test
    void repeatedGenerationReturnsExistingFileWithoutRewriting() {
        service.generate(7L, 2L, receiptSpec(null, false));
        ExcelTaskOutput existing = stored.get();
        when(outputs.findByOperatorIdAndIdempotencyKey(2L, existing.getIdempotencyKey())).thenReturn(Optional.of(existing));

        Map<String, Object> again = service.generate(7L, 2L, receiptSpec(null, false));

        assertThat(again.get("outputId")).isEqualTo(90L);
        verify(storage, times(1)).put(anyString(), any(byte[].class), anyString());
    }

    @Test
    void foreignAttachmentIsIgnoredWithWarningInsteadOfFailing() {
        when(attachments.findByIdInAndOperatorId(List.of(99L), 2L)).thenReturn(List.of());

        Map<String, Object> preview = service.preview(7L, 2L, receiptSpec(99L, true));

        assertThat(preview.get("sourceAttachment")).isNull();
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) preview.get("warnings");
        assertThat(warnings).anyMatch(item -> item.contains("已忽略来源附件"));
        assertThat(warnings).anyMatch(item -> item.contains("附件原文"));
        verify(storage, never()).put(anyString(), any(byte[].class), anyString());
    }

    @Test
    void rejectsOversizedWorkbooks() {
        AgentWorkbookSpec spec = new AgentWorkbookSpec();
        spec.setTitle("过大");
        List<AgentWorkbookSpec.SheetSpec> sheets = new ArrayList<>();
        for (int index = 0; index < AgentWorkbookService.MAX_SHEETS + 1; index++) {
            AgentWorkbookSpec.SheetSpec sheet = new AgentWorkbookSpec.SheetSpec();
            sheet.setName("表" + index);
            sheet.setColumns(List.of(new AgentWorkbookSpec.ColumnSpec("列", null, null)));
            sheet.setRows(List.of(List.of("值")));
            sheets.add(sheet);
        }
        spec.setSheets(sheets);
        assertThatThrownBy(() -> service.preview(7L, 2L, spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工作表不能超过");
    }

    @Test
    void parsesCurrencyTextAndDates() {
        assertThat(AgentWorkbookService.parseNumber("¥ 1,250.50")).isEqualByComparingTo("1250.50");
        assertThat(AgentWorkbookService.parseNumber("12元")).isEqualByComparingTo("12");
        assertThat(AgentWorkbookService.parseNumber("10支")).isNull();
        assertThat(AgentWorkbookService.parseDate("2026年10月6日")).isEqualTo("2026-10-06");
        assertThat(AgentWorkbookService.parseDate("2026/10/06")).isEqualTo("2026-10-06");
        assertThat(AgentWorkbookService.parseDate("十月六日")).isNull();
    }

    private AgentWorkbookSpec receiptSpec(Long attachmentId, boolean includeSource) {
        AgentWorkbookSpec spec = new AgentWorkbookSpec();
        spec.setTitle("恒迪模具五金收款收据0002766");
        spec.setAttachmentId(attachmentId);
        spec.setIncludeSourceText(includeSource);
        AgentWorkbookSpec.SheetSpec sheet = new AgentWorkbookSpec.SheetSpec();
        sheet.setName("商品明细");
        sheet.setColumns(List.of(
                new AgentWorkbookSpec.ColumnSpec("行号", "INTEGER", null),
                new AgentWorkbookSpec.ColumnSpec("项目", "TEXT", null),
                new AgentWorkbookSpec.ColumnSpec("单位", "TEXT", null),
                new AgentWorkbookSpec.ColumnSpec("数量", null, "QUANTITY"),
                new AgentWorkbookSpec.ColumnSpec("单价", null, "UNIT_PRICE"),
                new AgentWorkbookSpec.ColumnSpec("金额", null, "AMOUNT")));
        sheet.setRows(List.of(
                Arrays.asList(1, "钻头 2.2", "支", 10, 1.5, 15),
                Arrays.asList(2, "气管 10", "米", 28, 1.8, 504),
                Arrays.asList(3, "=危险公式", "支", 2, 40, null)));
        spec.setSheets(List.of(sheet));
        spec.setInfo(List.of(new AgentWorkbookSpec.InfoEntry("开票方", "恒迪模具五金"),
                new AgentWorkbookSpec.InfoEntry("单号", "0002766")));
        return spec;
    }
}
