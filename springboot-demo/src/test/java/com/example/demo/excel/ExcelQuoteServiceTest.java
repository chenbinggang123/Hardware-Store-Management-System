package com.example.demo.excel;

import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.entity.Product;
import com.example.demo.excel.dto.ExcelQuoteRequest;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.entity.ExcelTaskRowResult;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.excel.repository.ExcelTaskRowResultRepository;
import com.example.demo.excel.service.ExcelQuoteService;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExcelQuoteServiceTest {
    @Test
    void createsDownloadableWorkbookAndEscapesSpreadsheetFormulaText() throws Exception {
        ExcelTaskRepository taskRepository = mock(ExcelTaskRepository.class);
        ExcelTaskRowResultRepository rowRepository = mock(ExcelTaskRowResultRepository.class);
        ExcelTaskOutputRepository outputRepository = mock(ExcelTaskOutputRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        AttachmentStorage storage = mock(AttachmentStorage.class);
        ExcelTask task = task(10L, 2L, 6L);
        ExcelTaskRowResult row = row(101L, 10L, 3, 55L,
                "{\"PRODUCT_NAME\":\"=HYPERLINK(\\\"bad\\\")\",\"QUANTITY\":\"2\"}");
        Product product = new Product();
        product.setId(55L);
        product.setName("安全锤");
        product.setSpec("中号");
        product.setUnit("把");
        product.setRetailPrice(new BigDecimal("12.50"));
        AtomicReference<ExcelTaskOutput> output = new AtomicReference<>();
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        when(taskRepository.findByIdAndOperatorId(10L, 2L)).thenReturn(Optional.of(task));
        when(outputRepository.findByOperatorIdAndIdempotencyKey(eq(2L), anyString())).thenReturn(Optional.empty());
        when(outputRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ExcelTaskOutput value = invocation.getArgument(0);
            value.setId(80L);
            output.set(value);
            return value;
        });
        when(outputRepository.findById(80L)).thenAnswer(ignored -> Optional.of(output.get()));
        when(outputRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(rowRepository.findByTaskIdOrderBySheetIdAscRowNumberAsc(10L)).thenReturn(java.util.List.of(row));
        when(productRepository.findById(55L)).thenReturn(Optional.of(product));
        doAnswer(invocation -> {
            bytes.set(invocation.getArgument(1));
            return null;
        }).when(storage).put(anyString(), any(byte[].class), anyString());
        ExcelQuoteService service = new ExcelQuoteService(taskRepository, rowRepository, outputRepository,
                productRepository, customerRepository, storage, new ObjectMapper(), Runnable::run);

        var result = service.generate(10L,
                new ExcelQuoteRequest(6L, "RETAIL_PRICE", null, true, "quote-key-001"), 2L);

        assertEquals("READY", result.status());
        assertNotNull(bytes.get());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.get()))) {
            assertEquals(3, workbook.getNumberOfSheets());
            assertEquals("'=HYPERLINK(\"bad\")",
                    workbook.getSheet("报价明细").getRow(1).getCell(1).getStringCellValue());
            assertEquals(25.0, workbook.getSheet("报价明细").getRow(1).getCell(7).getNumericCellValue());
        }
    }

    private ExcelTask task(Long id, Long operatorId, Long version) {
        ExcelTask value = new ExcelTask();
        value.setId(id);
        value.setOperatorId(operatorId);
        value.setPurpose("BUYER_QUOTE");
        value.setVersion(version);
        return value;
    }

    private ExcelTaskRowResult row(Long id, Long taskId, int number, Long productId, String normalized) {
        ExcelTaskRowResult value = new ExcelTaskRowResult();
        value.setId(id);
        value.setTaskId(taskId);
        value.setSheetId(1L);
        value.setRowNumber(number);
        value.setMatchStatus("MATCHED");
        value.setMatchedProductId(productId);
        value.setNormalizedJson(normalized);
        return value;
    }
}
