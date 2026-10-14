package com.example.demo.finance;

import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.entity.Customer;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.finance.dto.StatementExportRequest;
import com.example.demo.finance.entity.AccountDocument;
import com.example.demo.finance.entity.AccountPayment;
import com.example.demo.finance.repository.AccountDocumentRepository;
import com.example.demo.finance.repository.AccountPaymentRepository;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.SupplierRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinanceStatementServiceTest {
    @Test
    void exportsTwoSheetWorkbookWithTypedDatesAndAmounts() throws Exception {
        AccountDocumentRepository documents = mock(AccountDocumentRepository.class);
        AccountPaymentRepository payments = mock(AccountPaymentRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        SupplierRepository suppliers = mock(SupplierRepository.class);
        ExcelTaskOutputRepository outputs = mock(ExcelTaskOutputRepository.class);
        AttachmentStorage storage = mock(AttachmentStorage.class);

        Customer customer = new Customer();
        customer.setId(8L);
        customer.setName("张师傅");
        when(customers.findById(8L)).thenReturn(Optional.of(customer));
        when(outputs.findByOperatorIdAndIdempotencyKey(9L, "statement-test-1")).thenReturn(Optional.empty());
        when(outputs.saveAndFlush(any(ExcelTaskOutput.class))).thenAnswer(invocation -> {
            ExcelTaskOutput output = invocation.getArgument(0);
            output.setId(30L);
            return output;
        });
        when(outputs.save(any(ExcelTaskOutput.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountDocument document = new AccountDocument();
        document.setDirection("RECEIVABLE");
        document.setCounterpartyType("CUSTOMER");
        document.setCounterpartyId(8L);
        document.setDocumentDate(LocalDate.of(2026, 10, 2));
        document.setDueDate(LocalDate.of(2026, 11, 1));
        document.setDocumentNumber("XS-1001");
        document.setTotalAmount(new BigDecimal("1250.50"));
        document.setSettledAmount(new BigDecimal("250.00"));
        document.setOutstandingAmount(new BigDecimal("1000.50"));
        document.setStatus("PARTIAL");
        when(documents.findByOperatorIdOrderByDocumentDateDescIdDesc(9L)).thenReturn(List.of(document));

        AccountPayment payment = new AccountPayment();
        payment.setDirection("RECEIVE");
        payment.setCounterpartyType("CUSTOMER");
        payment.setCounterpartyId(8L);
        payment.setPaymentDate(LocalDate.of(2026, 10, 8));
        payment.setPaymentMethod("微信");
        payment.setAmount(new BigDecimal("250.00"));
        payment.setUnallocatedAmount(BigDecimal.ZERO);
        when(payments.findByOperatorIdOrderByPaymentDateDescIdDesc(9L)).thenReturn(List.of(payment));

        AtomicReference<byte[]> saved = new AtomicReference<>();
        doAnswer(invocation -> { saved.set(invocation.getArgument(1)); return null; })
                .when(storage).put(any(), any(byte[].class), any());
        FinanceStatementService service = new FinanceStatementService(documents, payments, customers, suppliers,
                outputs, storage, new ObjectMapper());

        var result = service.export(9L, request("statement-test-1"));

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.name()).contains("张师傅-往来账对账单");
        assertThat(result.downloadPath()).isEqualTo("/agent/outputs/30/download");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(saved.get()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("往来账");
            assertThat(workbook.getSheetName(1)).isEqualTo("收付款");
            assertThat(workbook.getSheet("往来账").getRow(6).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(workbook.getSheet("往来账").getRow(6).getCell(4).getNumericCellValue()).isEqualTo(1250.50);
            assertThat(workbook.getSheet("收付款").getRow(6).getCell(2).getNumericCellValue()).isEqualTo(250.00);
        }
    }

    @Test
    void emptyStatementStillCreatesValidWorkbook() throws Exception {
        AccountDocumentRepository documents = mock(AccountDocumentRepository.class);
        AccountPaymentRepository payments = mock(AccountPaymentRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        ExcelTaskOutputRepository outputs = mock(ExcelTaskOutputRepository.class);
        AttachmentStorage storage = mock(AttachmentStorage.class);
        Customer customer = new Customer(); customer.setId(8L); customer.setName("空白客户");
        when(customers.findById(8L)).thenReturn(Optional.of(customer));
        when(outputs.findByOperatorIdAndIdempotencyKey(9L, "statement-test-2")).thenReturn(Optional.empty());
        when(outputs.saveAndFlush(any())).thenAnswer(invocation -> { ExcelTaskOutput output = invocation.getArgument(0); output.setId(31L); return output; });
        when(outputs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(documents.findByOperatorIdOrderByDocumentDateDescIdDesc(9L)).thenReturn(List.of());
        when(payments.findByOperatorIdOrderByPaymentDateDescIdDesc(9L)).thenReturn(List.of());
        AtomicReference<byte[]> saved = new AtomicReference<>();
        doAnswer(invocation -> { saved.set(invocation.getArgument(1)); return null; }).when(storage).put(any(), any(byte[].class), any());
        FinanceStatementService service = new FinanceStatementService(documents, payments, customers,
                mock(SupplierRepository.class), outputs, storage, new ObjectMapper());

        service.export(9L, request("statement-test-2"));

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(saved.get()))) {
            assertThat(workbook.getSheet("往来账").getRow(6).getCell(4).getNumericCellValue()).isZero();
            assertThat(workbook.getSheet("收付款").getRow(6).getCell(2).getNumericCellValue()).isZero();
        }
    }

    private StatementExportRequest request(String key) {
        StatementExportRequest request = new StatementExportRequest();
        request.setDirection("RECEIVABLE");
        request.setCounterpartyId(8L);
        request.setDateFrom(LocalDate.of(2026, 10, 1));
        request.setDateTo(LocalDate.of(2026, 10, 31));
        request.setIdempotencyKey(key);
        return request;
    }
}
