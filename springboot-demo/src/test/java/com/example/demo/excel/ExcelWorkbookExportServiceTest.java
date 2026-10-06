package com.example.demo.excel;

import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.excel.dto.ExcelWorkbookExportRequest;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.entity.ExcelTaskRow;
import com.example.demo.excel.entity.ExcelTaskSheet;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.excel.repository.ExcelTaskRowRepository;
import com.example.demo.excel.repository.ExcelTaskSheetRepository;
import com.example.demo.excel.service.ExcelWorkbookExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExcelWorkbookExportServiceTest {
    @Test
    void convertsParsedRowsToEditableXlsx() throws Exception {
        ExcelTaskRepository tasks = mock(ExcelTaskRepository.class);
        ExcelTaskSheetRepository sheets = mock(ExcelTaskSheetRepository.class);
        ExcelTaskRowRepository rows = mock(ExcelTaskRowRepository.class);
        ExcelTaskOutputRepository outputs = mock(ExcelTaskOutputRepository.class);
        AttachmentStorage storage = mock(AttachmentStorage.class);
        ExcelTask task = new ExcelTask();
        task.setId(7L); task.setOperatorId(2L); task.setVersion(3L); task.setOriginalName("采购单.pdf");
        ExcelTaskSheet sheet = new ExcelTaskSheet();
        sheet.setId(11L); sheet.setTaskId(7L); sheet.setSheetIndex(0); sheet.setSheetName("左侧清单");
        sheet.setColumnsJson("[\"商品名称\",\"数量\"]");
        ExcelTaskRow row = new ExcelTaskRow();
        row.setTaskId(7L); row.setSheetId(11L); row.setRowNumber(2);
        row.setCellsJson("[\"=危险公式\",\"12\"]");
        AtomicReference<ExcelTaskOutput> storedOutput = new AtomicReference<>();
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        when(tasks.findByIdAndOperatorId(7L, 2L)).thenReturn(Optional.of(task));
        when(outputs.findByOperatorIdAndIdempotencyKey(2L, "export-key-001")).thenReturn(Optional.empty());
        when(outputs.saveAndFlush(any())).thenAnswer(invocation -> {
            ExcelTaskOutput value = invocation.getArgument(0); value.setId(90L); storedOutput.set(value); return value;
        });
        when(outputs.findById(90L)).thenAnswer(ignored -> Optional.of(storedOutput.get()));
        when(outputs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sheets.findByTaskIdOrderBySheetIndexAsc(7L)).thenReturn(List.of(sheet));
        when(rows.findByTaskIdAndSheetIdOrderByRowNumberAsc(7L, 11L)).thenReturn(List.of(row));
        doAnswer(invocation -> { bytes.set(invocation.getArgument(1)); return null; })
                .when(storage).put(anyString(), any(byte[].class), anyString());
        ExcelWorkbookExportService service = new ExcelWorkbookExportService(tasks, sheets, rows, outputs,
                storage, new ObjectMapper(), Runnable::run);

        var result = service.generate(7L, new ExcelWorkbookExportRequest(3L, "export-key-001"), 2L);

        assertThat(result.status()).isEqualTo("READY");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.get()))) {
            var exported = workbook.getSheet("左侧清单");
            assertThat(exported.getRow(1).getCell(0).getStringCellValue()).isEqualTo("'=危险公式");
            assertThat(exported.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(12d);
        }
    }
}
