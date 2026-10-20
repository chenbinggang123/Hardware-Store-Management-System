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
        when(storage.stat(anyString())).thenAnswer(ignored -> new com.example.demo.agent.service.attachment.StoredObjectMetadata(bytes.get().length, "test"));
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
        attachment.setExtractedText("第1行 钻头 2.2 支 10 1.5 15\n第7行 示例管件乙 米 28 1.8 504");
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
        assertThat(saved.getOriginalName()).startsWith("示例五金店收款收据MOCK-1001_").endsWith(".xlsx");
        assertThat(saved.getStatus()).isEqualTo("READY");
        assertThat(saved.getSummaryJson()).contains("\"mismatchCount\":1");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.get()))) {
            Sheet sheet = workbook.getSheet("商品明细");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(3).getCell(7).getStringCellValue()).isEqualTo(AgentWorkbookService.NOTE_COLUMN);

            Row first = sheet.getRow(4);
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("钻头 2.2");
            assertThat(first.getCell(3).getNumericCellValue()).isEqualTo(10d);
            assertThat(first.getCell(5).getNumericCellValue()).isEqualTo(15d);
            assertThat(first.getCell(7).getStringCellValue()).isEmpty();

            Row second = sheet.getRow(5);
            assertThat(second.getCell(5).getNumericCellValue()).isEqualTo(504d);
            assertThat(second.getCell(5).getCellStyle().getFillForegroundColor())
                    .isEqualTo(IndexedColors.LIGHT_YELLOW.getIndex());
            assertThat(second.getCell(7).getStringCellValue()).contains("28×1.8=50.40").contains("不一致");

            Row third = sheet.getRow(6);
            assertThat(third.getCell(1).getStringCellValue()).isEqualTo("'=危险公式");
            assertThat(third.getCell(5).getStringCellValue()).isEmpty();
            assertThat(third.getCell(6).getNumericCellValue()).isEqualTo(80d);
            assertThat(third.getCell(7).getStringCellValue()).contains("票面金额未提供").contains("不代表原单金额");

            Row total = sheet.getRow(7);
            assertThat(total.getCell(0).getStringCellValue()).isEqualTo("合计");
            Cell sum = total.getCell(5);
            assertThat(sum.getCellFormula()).isEqualTo("IF(COUNT(F5:F7)=0,\"\",SUM(F5:F7))");
            assertThat(sum.getNumericCellValue()).isEqualTo(519d);
            assertThat(total.getCell(6).getNumericCellValue()).isEqualTo(145.4d);

            Sheet info = workbook.getSheet(AgentWorkbookService.INFO_SHEET);
            assertThat(info.getRow(1).getCell(0).getStringCellValue()).isEqualTo("开票方");
            assertThat(info.getRow(2).getCell(1).getStringCellValue()).isEqualTo("MOCK-1001");

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
        sheet.setRows(List.of(Arrays.asList("MOCK-1001", "12件"), Arrays.asList(null, "")));
        spec.setSheets(List.of(sheet));

        Map<String, Object> preview = service.preview(7L, 2L, spec);

        assertThat(preview.get("kind")).isEqualTo("EXCEL_WORKBOOK");
        assertThat(preview.get("rowCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sheets = (List<Map<String, Object>>) preview.get("sheets");
        assertThat(sheets.get(0).get("sampleRows")).isEqualTo(List.of(List.of("MOCK-1001", "12件")));
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

    @Test
    void fourColumnReceiptGetsCleanNameOrderLayoutAndLiveCalculatedAmounts() throws Exception {
        AgentWorkbookSpec spec = new AgentWorkbookSpec();
        spec.setTitle("单据识别明细_066636417cb8488c1fb20555f9801eb4-20261019-0142.xlsx");
        AgentWorkbookSpec.SheetSpec sheet = new AgentWorkbookSpec.SheetSpec();
        sheet.setName("单据明细");
        sheet.setColumns(List.of(new AgentWorkbookSpec.ColumnSpec("项目", "TEXT", null),
                new AgentWorkbookSpec.ColumnSpec("单位", "TEXT", null), new AgentWorkbookSpec.ColumnSpec("数量", "NUMBER", null),
                new AgentWorkbookSpec.ColumnSpec("单价", "MONEY", null)));
        sheet.setRows(List.of(Arrays.asList("√ 示例材料甲", "", 5, 19), Arrays.asList("√ 示例材料乙", "", 5, 5),
                Arrays.asList("√ 示例零件丙", "个", 1, 0.5), Arrays.asList("√ 示例零件丁", "", 100, 0.5), Arrays.asList("示例工具戊", "套", 10, 5)));
        spec.setSheets(List.of(sheet));
        spec.setInfo(List.of(new AgentWorkbookSpec.InfoEntry("票面合计原文", "拾万仟24拾元角分 ￥:260")));

        var preview = service.preview(101L, 2L, spec);
        assertThat((List<String>) preview.get("warnings")).anyMatch(value -> value.contains("220.50") && value.contains("260.00") && value.contains("39.50"));
        service.generate(101L, 2L, spec);
        assertThat(stored.get().getOriginalName()).matches("单据明细_\\d{8}_\\d{6}\\.xlsx");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.get()))) {
            Sheet actual = workbook.getSheetAt(0);
            assertThat(actual.getRow(3).getCell(5).getStringCellValue()).isEqualTo("核算金额");
            assertThat(actual.getRow(4).getCell(0).getNumericCellValue()).isEqualTo(1);
            assertThat(actual.getRow(4).getCell(1).getStringCellValue()).isEqualTo("√ 示例材料甲");
            assertThat(actual.getRow(4).getCell(2).getStringCellValue()).isEmpty();
            assertThat(actual.getRow(4).getCell(5).getCellFormula()).contains("D5*E5");
            assertThat(actual.getRow(9).getCell(5).getNumericCellValue()).isEqualTo(220.5);
            assertThat(actual.getRow(2).getCell(0).getStringCellValue()).contains("未提供").doesNotContain("慈溪");
            assertThat(actual.getPrintSetup().getLandscape()).isTrue();
            assertThat(actual.getPrintSetup().getFitWidth()).isEqualTo((short)1);
            assertThat(actual.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)4);
            actual.getRow(4).getCell(3).setCellValue(10);
            org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator.evaluateAllFormulaCells(workbook);
            assertThat(actual.getRow(4).getCell(5).getNumericCellValue()).isEqualTo(190);
            assertThat(actual.getRow(9).getCell(5).getNumericCellValue()).isEqualTo(315.5);
        }
        when(outputs.findByIdAndOperatorId(90L, 2L)).thenReturn(Optional.of(stored.get()));
        when(storage.openStream(anyString())).thenAnswer(ignored -> new ByteArrayInputStream(bytes.get()));
        var editContent = new AgentOutputService(outputs, storage, new ObjectMapper()).readWorkbookContent(90L, 2L, null, 100);
        var editedSheets = (List<Map<String, Object>>) editContent.get("sheets");
        assertThat(editedSheets.get(0).get("rowCount")).isEqualTo(5);
        assertThat((List<?>) editedSheets.get(0).get("rows")).hasSize(5);
    }

    @Test
    void missingInputsStayBlankAndRealZeroStillCalculates() throws Exception {
        var spec = new AgentWorkbookSpec(); spec.setTitle("缺项测试"); var sheet = new AgentWorkbookSpec.SheetSpec(); sheet.setName("明细");
        sheet.setColumns(List.of(new AgentWorkbookSpec.ColumnSpec("商品", "TEXT", null), new AgentWorkbookSpec.ColumnSpec("数量", "NUMBER", null), new AgentWorkbookSpec.ColumnSpec("单价", "MONEY", null)));
        sheet.setRows(List.of(Arrays.asList("缺数量", null, 19), Arrays.asList("真实零数量", 0, 19))); spec.setSheets(List.of(sheet));
        service.generate(103L, 2L, spec);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.get()))) {
            var actual = workbook.getSheetAt(0);
            assertThat(actual.getRow(4).getCell(4).getStringCellValue()).isEmpty();
            assertThat(actual.getRow(5).getCell(4).getNumericCellValue()).isZero();
            assertThat(actual.getRow(6).getCell(4).getNumericCellValue()).isZero();
        }
    }

    @Test
    void unknownPricingAdjustmentsDoNotProduceUndiscountedAmounts() throws Exception {
        var spec = new AgentWorkbookSpec(); spec.setTitle("折扣单");
        var sheet = new AgentWorkbookSpec.SheetSpec(); sheet.setName("明细");
        sheet.setColumns(List.of(new AgentWorkbookSpec.ColumnSpec("商品", "TEXT", null), new AgentWorkbookSpec.ColumnSpec("数量", null, "QUANTITY"),
                new AgentWorkbookSpec.ColumnSpec("单价", null, "UNIT_PRICE"), new AgentWorkbookSpec.ColumnSpec("折扣", "NUMBER", null), new AgentWorkbookSpec.ColumnSpec("金额", null, "AMOUNT")));
        sheet.setRows(List.of(List.of("商品", 2, 10, 0.57, 11.4))); spec.setSheets(List.of(sheet));
        service.generate(102L, 2L, spec);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.get()))) {
            var actual = workbook.getSheetAt(0);
            assertThat(actual.getRow(4).getCell(5).getNumericCellValue()).isEqualTo(11.4);
            assertThat(actual.getRow(4).getCell(6).getStringCellValue()).isEmpty();
            assertThat(actual.getRow(4).getCell(7).getStringCellValue()).contains("计价规则需确认");
        }
    }

    @Test
    void receiptPresentationLinksSourceAmountsAndRemainsEditableWithoutMixingSourceAndCalculated() throws Exception {
        AgentWorkbookSpec spec = receiptSpec(null, false); spec.setLayout("RECEIPT");
        spec.setInfo(List.of(new AgentWorkbookSpec.InfoEntry("公司名称", "示例五金店"),
                new AgentWorkbookSpec.InfoEntry("单号", "MOCK-1002"), new AgentWorkbookSpec.InfoEntry("日期", "2026-10-02")));
        spec.getSheets().get(0).setRows(List.of(Arrays.asList(1, "示例材料甲", null, 5, 17, 85), Arrays.asList(2, "示例材料乙", null, 5, 5, 25)));
        service.generate(200L, 2L, spec);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.get()))) {
            var receipt = workbook.getSheetAt(0); var data = workbook.getSheet("商品明细");
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            assertThat(receipt.getSheetName()).isEqualTo("收据版式");
            assertThat(receipt.getRow(0).getCell(0).getStringCellValue()).isEqualTo("示例五金店");
            assertThat(receipt.getRow(0).getCell(6).getStringCellValue()).isEqualTo("单据编号：MOCK-1002");
            assertThat(receipt.getRow(1).getCell(7).getLocalDateTimeCellValue().toLocalDate()).hasToString("2026-10-02");
            assertThat(new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(receipt.getRow(1).getCell(7))).isEqualTo("2026-10-02");
            assertThat(receipt.getRow(3).getCell(6).getStringCellValue()).isEqualTo("金额（元）");
            assertThat(receipt.getRow(3).getLastCellNum()).isEqualTo((short)8);
            assertThat(evaluator.evaluate(receipt.getRow(4).getCell(6)).getNumberValue()).isEqualTo(85);
            assertThat(evaluator.evaluate(receipt.getRow(11).getCell(6)).getNumberValue()).isEqualTo(110);
            assertThat(evaluator.evaluate(receipt.getRow(4).getCell(3)).getStringValue()).isEmpty();
            // Changing qty updates calculated total only; original amount remains 85 until explicitly edited.
            data.getRow(4).getCell(3).setCellValue(10); evaluator.clearAllCachedResultValues();
            assertThat(evaluator.evaluate(receipt.getRow(4).getCell(4)).getNumberValue()).isEqualTo(10);
            assertThat(evaluator.evaluate(receipt.getRow(12).getCell(0)).getStringValue()).contains("195.00");
            assertThat(evaluator.evaluate(receipt.getRow(11).getCell(6)).getNumberValue()).isEqualTo(110);
            data.getRow(4).getCell(5).setCellValue(80.05); evaluator.clearAllCachedResultValues();
            assertThat(evaluator.evaluate(receipt.getRow(4).getCell(6)).getNumberValue()).isEqualTo(80.05);
            assertThat(receipt.getPrintSetup().getLandscape()).isTrue();
            assertThat(receipt.getPrintSetup().getFitHeight()).isZero();
            assertThat(workbook.getPrintArea(0)).contains("$A$1:$H$14");
        }
        when(storage.openStream(anyString())).thenAnswer(ignored -> new ByteArrayInputStream(bytes.get()));
        when(outputs.findByIdAndOperatorId(90L, 2L)).thenReturn(Optional.of(stored.get()));
        var reader = new AgentOutputService(outputs, storage, new ObjectMapper()).readWorkbookContent(90L, 2L, null, 100);
        assertThat(reader.get("layout")).isEqualTo("RECEIPT");
        assertThat((List<?>) reader.get("sheets")).hasSize(1);
    }

    @Test
    void receiptMissingAmountStaysBlankAndLargeMoneyDoesNotLoseDigits() throws Exception {
        AgentWorkbookSpec spec = receiptSpec(null, false); spec.setLayout("RECEIPT");
        service.generate(201L, 2L, spec);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.get()))) {
            var receipt = workbook.getSheetAt(0); var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            assertThat(evaluator.evaluate(receipt.getRow(6).getCell(6)).getStringValue()).isEmpty();
            assertThat(evaluator.evaluate(receipt.getRow(11).getCell(6)).getStringValue()).isEmpty();
        }
        spec.getSheets().get(0).setRows(List.of(List.of(1,"商品","个",1,1000000,1000000)));
        service.generate(202L, 2L, spec);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.get()))) {
            assertThat(workbook.getCreationHelper().createFormulaEvaluator().evaluate(workbook.getSheetAt(0).getRow(4).getCell(6)).getNumberValue()).isEqualTo(1000000);
        }
    }

    @Test
    void digitalBillAmountFollowsFormulaWithoutClaimingReceiptPayment() throws Exception {
        var spec=receiptSpec(null,false);spec.setLayout("RECEIPT");
        spec.setInfo(List.of(new AgentWorkbookSpec.InfoEntry("单据来源","程序开单"),new AgentWorkbookSpec.InfoEntry("单据名称","销售单")));
        service.generate(300L,2L,spec);
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes.get()))) {
            var paper=workbook.getSheetAt(0);var evaluator=workbook.getCreationHelper().createFormulaEvaluator();
            assertThat(paper.getRow(0).getCell(3).getStringCellValue()).isEqualTo("销售单");
            assertThat(evaluator.evaluate(paper.getRow(4).getCell(6)).getNumberValue()).isEqualTo(15);
            workbook.getSheet("商品明细").getRow(4).getCell(3).setCellValue(20);evaluator.clearAllCachedResultValues();
            assertThat(evaluator.evaluate(paper.getRow(4).getCell(6)).getNumberValue()).isEqualTo(30);
            assertThat(evaluator.evaluate(paper.getRow(12).getCell(0)).getStringValue()).contains("尚未登记").doesNotContain("票面");
        }
    }

    @Test
    void receiptLongNamesSpecsAndRemarksExpandRowsInsteadOfClipping() throws Exception {
        var spec=receiptSpec(null,false);spec.setLayout("RECEIPT");spec.setInfo(List.of(new AgentWorkbookSpec.InfoEntry("公司名称","五金".repeat(30)),new AgentWorkbookSpec.InfoEntry("备注","备注".repeat(60))));
        spec.getSheets().get(0).setColumns(new ArrayList<>(spec.getSheets().get(0).getColumns()));
        spec.getSheets().get(0).getColumns().add(new AgentWorkbookSpec.ColumnSpec("备注","TEXT",null));
        spec.getSheets().get(0).setRows(List.of(List.of(1,"长商品名称".repeat(15),"套",2,5,10,"核对说明".repeat(25))));
        service.generate(301L,2L,spec);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes.get()))) {assertThat(book.getSheetAt(0).getRow(0).getHeightInPoints()).isGreaterThan(39);assertThat(book.getSheetAt(0).getRow(4).getHeightInPoints()).isGreaterThan(27);}
    }

    private AgentWorkbookSpec receiptSpec(Long attachmentId, boolean includeSource) {
        AgentWorkbookSpec spec = new AgentWorkbookSpec();
        spec.setTitle("示例五金店收款收据MOCK-1001");
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
                Arrays.asList(2, "示例管件乙", "米", 28, 1.8, 504),
                Arrays.asList(3, "=危险公式", "支", 2, 40, null)));
        spec.setSheets(List.of(sheet));
        spec.setInfo(List.of(new AgentWorkbookSpec.InfoEntry("开票方", "示例五金店"),
                new AgentWorkbookSpec.InfoEntry("单号", "MOCK-1001")));
        return spec;
    }
}
