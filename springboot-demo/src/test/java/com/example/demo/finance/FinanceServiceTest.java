package com.example.demo.finance;

import com.example.demo.entity.Customer;
import com.example.demo.filecenter.repository.FileCenterActivityRepository;
import com.example.demo.filecenter.repository.FileCenterMetadataRepository;
import com.example.demo.finance.dto.AccountDocumentDetail;
import com.example.demo.finance.dto.AccountDocumentRequest;
import com.example.demo.finance.dto.CounterpartyPaymentRequest;
import com.example.demo.finance.dto.FinanceOverview;
import com.example.demo.finance.dto.PaymentAllocationResult;
import com.example.demo.finance.dto.PaymentRecordRequest;
import com.example.demo.finance.entity.AccountDocument;
import com.example.demo.finance.entity.AccountPayment;
import com.example.demo.finance.entity.PaymentAllocation;
import com.example.demo.finance.repository.AccountDocumentRepository;
import com.example.demo.finance.repository.AccountPaymentRepository;
import com.example.demo.finance.repository.PaymentAllocationRepository;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.SupplierRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinanceServiceTest {
    @Test
    void createsReceivableAndRecordsPartialPayments() {
        AccountDocumentRepository documentRepository = mock(AccountDocumentRepository.class);
        AccountPaymentRepository paymentRepository = mock(AccountPaymentRepository.class);
        PaymentAllocationRepository allocationRepository = mock(PaymentAllocationRepository.class);
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        SupplierRepository supplierRepository = mock(SupplierRepository.class);
        FileCenterMetadataRepository fileMetadataRepository = mock(FileCenterMetadataRepository.class);
        FileCenterActivityRepository fileActivityRepository = mock(FileCenterActivityRepository.class);
        Customer customer = new Customer();
        customer.setId(8L);
        customer.setName("张师傅");
        when(customerRepository.findById(8L)).thenReturn(Optional.of(customer));

        AtomicReference<AccountDocument> storedDocument = new AtomicReference<>();
        List<AccountPayment> payments = new ArrayList<>();
        List<PaymentAllocation> allocations = new ArrayList<>();
        AtomicLong paymentIds = new AtomicLong();
        when(documentRepository.save(any(AccountDocument.class))).thenAnswer(invocation -> {
            AccountDocument item = invocation.getArgument(0);
            if (item.getId() == null) item.setId(1L);
            storedDocument.set(item);
            return item;
        });
        when(documentRepository.findByIdAndOperatorId(1L, 9L)).thenAnswer(ignored -> Optional.ofNullable(storedDocument.get()));
        when(documentRepository.findByOperatorIdOrderByDocumentDateDescIdDesc(9L)).thenAnswer(ignored -> List.of(storedDocument.get()));
        when(paymentRepository.save(any(AccountPayment.class))).thenAnswer(invocation -> {
            AccountPayment item = invocation.getArgument(0);
            item.setId(paymentIds.incrementAndGet());
            payments.add(item);
            return item;
        });
        when(paymentRepository.findAllById(any())).thenAnswer(ignored -> payments);
        when(paymentRepository.findByOperatorIdOrderByPaymentDateDescIdDesc(9L)).thenAnswer(ignored -> payments);
        when(allocationRepository.save(any(PaymentAllocation.class))).thenAnswer(invocation -> {
            PaymentAllocation item = invocation.getArgument(0);
            allocations.add(item);
            return item;
        });
        when(allocationRepository.findByDocumentIdOrderByCreateTimeDesc(1L)).thenAnswer(ignored -> allocations);

        FinanceService service = new FinanceService(documentRepository, paymentRepository, allocationRepository,
                customerRepository, supplierRepository, fileMetadataRepository, fileActivityRepository);
        AccountDocumentRequest request = new AccountDocumentRequest();
        request.setDirection("RECEIVABLE");
        request.setCounterpartyId(8L);
        request.setDocumentDate(LocalDate.of(2026, 10, 14));
        request.setTotalAmount(new BigDecimal("1000"));
        request.setSettledAmount(new BigDecimal("200"));
        AccountDocumentDetail created = service.create(9L, request);
        assertThat(created.document().outstandingAmount()).isEqualByComparingTo("800.00");

        PaymentRecordRequest payment = new PaymentRecordRequest();
        payment.setAmount(new BigDecimal("300"));
        payment.setPaymentDate(LocalDate.of(2026, 10, 14));
        payment.setPaymentMethod("微信");
        AccountDocumentDetail updated = service.recordPayment(9L, 1L, payment);
        assertThat(updated.document().settledAmount()).isEqualByComparingTo("500.00");
        assertThat(updated.document().outstandingAmount()).isEqualByComparingTo("500.00");
        assertThat(updated.document().status()).isEqualTo("PARTIAL");
        assertThat(updated.payments()).hasSize(2);

        FinanceOverview overview = service.overview(9L, null, "OPEN", null, YearMonth.of(2026, 10));
        assertThat(overview.receivableOutstanding()).isEqualByComparingTo("500.00");
        assertThat(overview.monthOccurred()).isEqualByComparingTo("1000.00");
        assertThat(overview.monthSettled()).isEqualByComparingTo("500.00");
    }

    @Test
    void allocatesCounterpartyPaymentToOldestDocumentsAndKeepsRemainder() {
        AccountDocumentRepository documentRepository = mock(AccountDocumentRepository.class);
        AccountPaymentRepository paymentRepository = mock(AccountPaymentRepository.class);
        PaymentAllocationRepository allocationRepository = mock(PaymentAllocationRepository.class);
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        SupplierRepository supplierRepository = mock(SupplierRepository.class);
        Customer customer = new Customer();
        customer.setId(8L);
        customer.setName("张师傅");
        when(customerRepository.findById(8L)).thenReturn(Optional.of(customer));

        AccountDocument first = openDocument(1L, "100.00", LocalDate.of(2026, 9, 1));
        AccountDocument second = openDocument(2L, "200.00", LocalDate.of(2026, 10, 1));
        when(documentRepository.findByOperatorIdAndDirectionAndCounterpartyTypeAndCounterpartyIdAndStatusNotOrderByDocumentDateAscIdAsc(
                9L, "RECEIVABLE", "CUSTOMER", 8L, "SETTLED")).thenReturn(List.of(first, second));
        when(documentRepository.save(any(AccountDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.save(any(AccountPayment.class))).thenAnswer(invocation -> {
            AccountPayment item = invocation.getArgument(0);
            item.setId(20L);
            return item;
        });
        List<PaymentAllocation> allocations = new ArrayList<>();
        when(allocationRepository.save(any(PaymentAllocation.class))).thenAnswer(invocation -> {
            PaymentAllocation item = invocation.getArgument(0);
            allocations.add(item);
            return item;
        });

        FinanceService service = new FinanceService(documentRepository, paymentRepository, allocationRepository,
                customerRepository, supplierRepository, mock(FileCenterMetadataRepository.class), mock(FileCenterActivityRepository.class));
        CounterpartyPaymentRequest request = new CounterpartyPaymentRequest();
        request.setDirection("RECEIVABLE");
        request.setCounterpartyId(8L);
        request.setAmount(new BigDecimal("350"));
        PaymentAllocationResult result = service.recordCounterpartyPayment(9L, request);

        assertThat(result.allocatedAmount()).isEqualByComparingTo("300.00");
        assertThat(result.unallocatedAmount()).isEqualByComparingTo("50.00");
        assertThat(result.documentCount()).isEqualTo(2);
        assertThat(allocations).extracting(PaymentAllocation::getAmount)
                .containsExactly(new BigDecimal("100.00"), new BigDecimal("200.00"));
        assertThat(first.getStatus()).isEqualTo("SETTLED");
        assertThat(second.getStatus()).isEqualTo("SETTLED");
    }

    private AccountDocument openDocument(Long id, String outstanding, LocalDate date) {
        AccountDocument item = new AccountDocument();
        item.setId(id);
        item.setTotalAmount(new BigDecimal(outstanding));
        item.setSettledAmount(BigDecimal.ZERO.setScale(2));
        item.setOutstandingAmount(new BigDecimal(outstanding));
        item.setStatus("UNPAID");
        item.setDocumentDate(date);
        return item;
    }
}
