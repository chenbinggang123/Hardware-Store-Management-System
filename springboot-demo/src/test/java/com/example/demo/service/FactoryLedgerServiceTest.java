package com.example.demo.service;

import com.example.demo.dto.FactoryLedgerTransactionRequest;
import com.example.demo.entity.FactoryLedger;
import com.example.demo.entity.FactoryLedgerSettlement;
import com.example.demo.entity.FactoryLedgerTransaction;
import com.example.demo.repository.FactoryLedgerRepository;
import com.example.demo.repository.FactoryLedgerSettlementRepository;
import com.example.demo.repository.FactoryLedgerTransactionRepository;
import com.example.demo.repository.OperationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FactoryLedgerServiceTest {
    private FactoryLedgerRepository ledgerRepository;
    private FactoryLedgerTransactionRepository transactionRepository;
    private FactoryLedgerSettlementRepository settlementRepository;
    private FactoryLedgerService service;
    private FactoryLedger ledger;

    @BeforeEach
    void setUp() {
        ledgerRepository = mock(FactoryLedgerRepository.class);
        transactionRepository = mock(FactoryLedgerTransactionRepository.class);
        settlementRepository = mock(FactoryLedgerSettlementRepository.class);
        OperationLogRepository operationLogRepository = mock(OperationLogRepository.class);
        service = new FactoryLedgerService(ledgerRepository, transactionRepository, settlementRepository, operationLogRepository);
        ledger = ledger();
        when(ledgerRepository.findById(1L)).thenReturn(Optional.of(ledger));
        when(ledgerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(settlementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void calculatesMonthlyBalanceUsingIntegerCents() {
        when(transactionRepository.findByLedgerIdOrderByTransactionDateDescCreateTimeDesc(1L)).thenReturn(List.of(
                transaction("PURCHASE", "2026-09-30", 1000),
                transaction("PURCHASE", "2026-10-09", 10560),
                transaction("RETURN", "2026-10-10", 2000),
                transaction("PAYMENT", "2026-10-11", 5000)
        ));
        when(settlementRepository.findByLedgerIdAndSettlementMonth(1L, "2026-10"))
                .thenReturn(Optional.empty());

        Map<String, Object> detail = service.get(1L, "2026-10");
        Map<?, ?> summary = (Map<?, ?>) detail.get("summary");

        assertEquals("138.50", summary.get("openingAmount"));
        assertEquals("105.60", summary.get("purchaseAmount"));
        assertEquals("20.00", summary.get("returnAmount"));
        assertEquals("50.00", summary.get("paymentAmount"));
        assertEquals("174.10", summary.get("balanceAmount"));
    }

    @Test
    void reversalRestoresBalanceAndCanOnlyHappenOnce() {
        FactoryLedgerTransaction purchase = transaction("PURCHASE", "2026-10-09", 10560);
        purchase.setId(11L);
        purchase.setLedgerId(1L);
        purchase.setTransactionNo("LS-1");
        when(transactionRepository.findById(11L)).thenReturn(Optional.of(purchase));

        Map<String, Object> reversal = service.reverseTransaction(1L, 11L, "数量记错", 7L);

        assertEquals("-10560", String.valueOf(reversal.get("amountCents")));
        assertEquals("-¥105.60", reversal.get("signedAmount"));
        assertEquals("REVERSED", purchase.getStatus());
        assertThrows(IllegalArgumentException.class, () -> service.reverseTransaction(1L, 11L, "再来一次", 7L));

        purchase.setStatus("REVERSED");
        FactoryLedgerTransaction saved = new FactoryLedgerTransaction();
        saved.setLedgerId(1L);
        saved.setType("PURCHASE");
        saved.setAmountCents(-10560L);
        saved.setTransactionDate(java.time.LocalDate.parse("2026-10-21"));
        when(transactionRepository.findByLedgerIdOrderByTransactionDateDescCreateTimeDesc(1L)).thenReturn(List.of(saved, purchase));
        Map<?, ?> summary = (Map<?, ?>) service.get(1L, "2026-10").get("summary");
        assertEquals("0.00", summary.get("purchaseAmount"));
    }

    @Test
    void newEntryDoesNotResetAConfirmedMonth() {
        com.example.demo.entity.FactoryLedgerSettlement confirmed = new com.example.demo.entity.FactoryLedgerSettlement();
        confirmed.setStatus("CONFIRMED");
        when(settlementRepository.findByLedgerIdAndSettlementMonth(1L, "2026-10")).thenReturn(Optional.of(confirmed));
        FactoryLedgerTransactionRequest request = new FactoryLedgerTransactionRequest();
        request.setType("PAYMENT");
        request.setDate(java.time.LocalDate.parse("2026-10-15"));
        request.setPaymentAmount(new java.math.BigDecimal("20"));

        Map<String, Object> result = service.addTransaction(1L, request, 7L);

        assertEquals("CONFIRMED", confirmed.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(result.get("warning"));
    }

    @Test
    void rejectsDuplicateReceiptBeforeWriting() {
        when(transactionRepository.existsByLedgerIdAndReceiptNo(1L, "A-001")).thenReturn(true);
        FactoryLedgerTransactionRequest request = new FactoryLedgerTransactionRequest();
        request.setType("PAYMENT");
        request.setReceiptNo("A-001");
        request.setPaymentAmount(new BigDecimal("50.00"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addTransaction(1L, request, 7L));

        assertEquals("这张收据已经记过账", error.getMessage());
    }

    @Test
    void persistsSettlementProgress() {
        when(settlementRepository.findByLedgerIdAndSettlementMonth(1L, "2026-10"))
                .thenReturn(Optional.of(new FactoryLedgerSettlement()));

        Map<String, Object> result = service.setSettlementStatus(1L, "2026-10", "CONFIRMED", 7L);

        assertEquals("CONFIRMED", result.get("status"));
        assertEquals("已对账", result.get("statusLabel"));
    }

    @Test
    void previewsBalanceWithoutWritingAndMarksConfirmedAgentEntry() {
        when(transactionRepository.findByLedgerIdOrderByTransactionDateDescCreateTimeDesc(1L)).thenReturn(List.of());
        when(settlementRepository.findByLedgerIdAndSettlementMonth(1L, "2026-10"))
                .thenReturn(Optional.empty());
        FactoryLedgerTransactionRequest request = new FactoryLedgerTransactionRequest();
        request.setType("PAYMENT");
        request.setDate(LocalDate.of(2026, 10, 11));
        request.setPaymentAmount(new BigDecimal("50.00"));

        Map<String, Object> preview = service.previewTransaction(1L, request);

        assertEquals("128.50", preview.get("balanceBefore"));
        assertEquals("78.50", preview.get("balanceAfter"));

        service.addAgentTransaction(1L, request, 7L);
        ArgumentCaptor<FactoryLedgerTransaction> transactionCaptor = ArgumentCaptor.forClass(FactoryLedgerTransaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        assertEquals("AGENT", transactionCaptor.getValue().getSource());
        assertEquals(7L, transactionCaptor.getValue().getOperatorId());
    }

    private FactoryLedger ledger() {
        FactoryLedger value = new FactoryLedger();
        value.setId(1L);
        value.setLedgerNo("ZB-20261001-TEST");
        value.setFactoryName("测试工厂");
        value.setStartDate(LocalDate.of(2026, 10, 1));
        value.setSettlementDay(31);
        value.setOpeningBalanceCents(12850L);
        value.setStatus("ACTIVE");
        value.setCreateTime(LocalDateTime.now());
        value.setUpdateTime(LocalDateTime.now());
        return value;
    }

    private FactoryLedgerTransaction transaction(String type, String date, long amountCents) {
        FactoryLedgerTransaction value = new FactoryLedgerTransaction();
        value.setType(type);
        value.setLedgerId(1L);
        value.setTransactionDate(LocalDate.parse(date));
        value.setAmountCents(amountCents);
        value.setCreateTime(LocalDateTime.now());
        return value;
    }
}
