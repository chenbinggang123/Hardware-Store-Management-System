package com.example.demo.billing;

import com.example.demo.dto.FactoryLedgerTransactionRequest;
import com.example.demo.entity.FactoryLedger;
import com.example.demo.entity.FactoryLedgerTransaction;
import com.example.demo.repository.FactoryLedgerRepository;
import com.example.demo.repository.FactoryLedgerTransactionRepository;
import com.example.demo.service.FactoryLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.contains;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingLedgerServiceTest {
    private static final String NUMBER = "KD-20261021-9";
    private final ObjectMapper mapper = new ObjectMapper();
    private BillingDocumentRepository documents;
    private FactoryLedgerService ledgers;
    private FactoryLedgerRepository ledgerRepository;
    private FactoryLedgerTransactionRepository transactions;
    private BillingLedgerService service;

    @BeforeEach
    void setUp() {
        documents = mock(BillingDocumentRepository.class);
        ledgers = mock(FactoryLedgerService.class);
        ledgerRepository = mock(FactoryLedgerRepository.class);
        transactions = mock(FactoryLedgerTransactionRepository.class);
        service = new BillingLedgerService(documents, ledgers, ledgerRepository, transactions, mapper);
        FactoryLedger ledger = new FactoryLedger();
        ledger.setId(3L);
        ledger.setFactoryName("恒达模具厂");
        when(ledgerRepository.findById(3L)).thenReturn(Optional.of(ledger));
    }

    @Test
    void confirmedDocumentBecomesOnePurchaseInChosenLedger() throws Exception {
        BillingDocument document = document("CONFIRMED", NUMBER, 3L);
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(document));
        FactoryLedgerTransaction saved = transaction(3L, 10560L, NUMBER, 1L, "ACTIVE");
        when(transactions.findBySourceAndReceiptNoStartingWith("BILLING", NUMBER))
                .thenReturn(List.of(), List.of(), List.of(saved));

        Map<String, Object> result = service.post(7L, 9L, null);

        ArgumentCaptor<FactoryLedgerTransactionRequest> request = ArgumentCaptor.forClass(FactoryLedgerTransactionRequest.class);
        verify(ledgers).addBillingTransaction(eq(3L), request.capture(), eq(7L));
        assertThat(request.getValue().getType()).isEqualTo("PURCHASE");
        assertThat(request.getValue().getReceiptNo()).isEqualTo(NUMBER);
        assertThat(request.getValue().getDate()).isEqualTo(LocalDate.parse("2026-10-21"));
        assertThat(request.getValue().getItems()).hasSize(1);
        assertThat(request.getValue().getItems().get(0).getUnitPrice()).isEqualByComparingTo("2.64");
        assertThat(result.get("posted")).isEqualTo(true);
        assertThat(result.get("factoryName")).isEqualTo("恒达模具厂");
        assertThat((BigDecimal) result.get("amount")).isEqualByComparingTo("105.60");
    }

    @Test
    void postingTwiceDoesNotCreateASecondEntry() throws Exception {
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(document("CONFIRMED", NUMBER, 3L)));
        when(transactions.findBySourceAndReceiptNoStartingWith("BILLING", NUMBER))
                .thenReturn(List.of(transaction(3L, 10560L, NUMBER, 1L, "ACTIVE")));

        assertThat(service.post(7L, 9L, 3L).get("posted")).isEqualTo(true);
        assertThatThrownBy(() -> service.post(7L, 9L, 4L)).hasMessageContaining("恒达模具厂");
        verify(ledgers, never()).addBillingTransaction(any(), any(), any());
    }

    @Test
    void postedDocumentsCanOnlyBeManagedFromLedger() throws Exception {
        BillingDocument saved = document("CONFIRMED", NUMBER, 3L);
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(saved));
        FactoryLedgerTransaction entry = transaction(3L, 10560L, NUMBER, 55L, "ACTIVE");
        when(transactions.findBySourceAndReceiptNoStartingWith("BILLING", NUMBER)).thenReturn(List.of(entry));
        assertThatThrownBy(() -> service.reopen(7L, 9L)).hasMessageContaining("账本中处理");
        assertThatThrownBy(() -> service.voidDocument(7L, 9L, "错了")).hasMessageContaining("账本中处理");
        assertThatThrownBy(() -> service.deleteDocument(7L, 9L)).hasMessageContaining("账本中处理");
        entry.setStatus("REVERSED");
        assertThatThrownBy(() -> service.deleteDocument(7L, 9L)).hasMessageContaining("账本中处理");
        verifyNoInteractions(ledgers);
        verify(documents, never()).delete(any());
        assertThat(saved.getStatus()).isEqualTo("CONFIRMED");
    }
    @Test
    void draftsAndDocumentsWithoutLedgerAreRejected() throws Exception {
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(document("DRAFT", null, 3L)));
        assertThatThrownBy(() -> service.post(7L, 9L, 3L)).hasMessageContaining("先保存单据");

        when(documents.lockOwned(10L, 7L)).thenReturn(Optional.of(document("CONFIRMED", "KD-20261021-10", null)));
        when(transactions.findBySourceAndReceiptNoStartingWith("BILLING", "KD-20261021-10")).thenReturn(List.of());
        assertThatThrownBy(() -> service.post(7L, 10L, null)).hasMessageContaining("选择");
        verify(ledgers, never()).addBillingTransaction(any(), any(), any());
    }

    @Test
    void deletingDraftRemovesItWithoutLedgerChanges() throws Exception {
        BillingDocument draft = document("DRAFT", null, null);
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(draft));
        service.deleteDocument(7L, 9L);
        verify(documents).delete(draft);
        verify(documents).flush();
        verifyNoInteractions(ledgers, transactions);
    }

    @Test
    void deletingPostedDocumentFromLedgerReversesOnlyActiveEntries() throws Exception {
        BillingDocument saved = document("CONFIRMED", NUMBER, 3L);
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(saved));
        when(documents.findByDocumentNumber(NUMBER)).thenReturn(Optional.of(saved));
        FactoryLedgerTransaction active = transaction(3L, 10560L, NUMBER + "-R2", 56L, "ACTIVE");
        when(transactions.findById(56L)).thenReturn(Optional.of(active));
        when(transactions.findBySourceAndReceiptNoStartingWith("BILLING", NUMBER)).thenReturn(List.of(
                transaction(3L, 10560L, NUMBER, 55L, "REVERSED"), active));
        assertThatThrownBy(() -> service.deleteFromLedger(7L, 4L, 56L)).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.deleteFromLedger(8L, 3L, 56L)).hasMessageContaining("无权");
        service.deleteFromLedger(7L, 3L, 56L);
        var sequence = inOrder(ledgers, documents);
        sequence.verify(ledgers).reverseTransaction(eq(3L), eq(56L), contains("删除单据"), eq(7L));
        sequence.verify(documents).delete(saved);
        verify(ledgers, never()).reverseTransaction(eq(3L), eq(55L), any(), any());
    }
    @Test
    void deletingAnotherOwnersDocumentIsRejected() {
        when(documents.lockOwned(9L, 8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteDocument(8L, 9L)).hasMessageContaining("单据不存在");
        verify(documents, never()).delete(any());
        verifyNoInteractions(ledgers);
    }

    @Test
    void failedLedgerReversalDoesNotDeleteDocument() throws Exception {
        BillingDocument saved = document("CONFIRMED", NUMBER, 3L);
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(saved));
        when(documents.findByDocumentNumber(NUMBER)).thenReturn(Optional.of(saved));
        FactoryLedgerTransaction active = transaction(3L, 10560L, NUMBER, 55L, "ACTIVE");
        when(transactions.findById(55L)).thenReturn(Optional.of(active));
        when(transactions.findBySourceAndReceiptNoStartingWith("BILLING", NUMBER)).thenReturn(List.of(active));
        org.mockito.Mockito.doThrow(new IllegalStateException("冲销失败")).when(ledgers).reverseTransaction(eq(3L),eq(55L),any(),eq(7L));
        assertThatThrownBy(() -> service.deleteFromLedger(7L,3L,55L)).hasMessageContaining("冲销失败");
        verify(documents, never()).delete(any());
    }
    @Test
    void batchDeletionLocksAndDeletesOnlyDistinctDrafts() throws Exception {
        BillingDocument first = document("DRAFT", null, null);
        BillingDocument second = document("DRAFT", null, null);
        second.setId(10L);
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(first));
        when(documents.lockOwned(10L, 7L)).thenReturn(Optional.of(second));
        assertThat(service.deleteDrafts(7L, List.of(10L, 9L, 9L))).isEqualTo(2);
        verify(documents).deleteAll(List.of(first, second));
        verifyNoInteractions(ledgers, transactions);
    }

    @Test
    void batchDeletionRejectsSavedDocumentsWithoutPartialDeletion() throws Exception {
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(document("DRAFT", null, null)));
        when(documents.lockOwned(10L, 7L)).thenReturn(Optional.of(document("CONFIRMED", NUMBER, 3L)));
        assertThatThrownBy(() -> service.deleteDrafts(7L, List.of(9L, 10L))).hasMessageContaining("本次没有删除");
        verify(documents, never()).deleteAll(any());
    }

    @Test
    void batchDeletionRejectsMissingAndEmptySelections() throws Exception {
        assertThatThrownBy(() -> service.deleteDrafts(7L, List.of())).hasMessageContaining("请选择");
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(document("DRAFT", null, null)));
        when(documents.lockOwned(10L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteDrafts(7L, List.of(9L, 10L))).hasMessageContaining("重新选择");
        verify(documents, never()).deleteAll(any());
    }

    @Test
    void statusFilterIsAppliedBeforePagination() {
        var pageable = org.springframework.data.domain.PageRequest.of(1, 20);
        when(documents.searchByStatus(7L, "客户", "DRAFT", pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(), pageable, 0));
        assertThat(service.records(7L, "客户", "DRAFT", 1, 20).get("items")).asList().isEmpty();
        verify(documents).searchByStatus(7L, "客户", "DRAFT", pageable);
        assertThatThrownBy(() -> service.records(7L, "", "INVALID", 0, 20)).hasMessageContaining("状态无效");
    }

    private BillingDocument document(String status, String number, Long ledgerId) throws Exception {
        BillingContent content = new BillingContent();
        content.setCustomerName("恒达模具厂");
        content.setDate("2026-10-21");
        content.setLedgerId(ledgerId);
        BillingContent.Line line = new BillingContent.Line();
        line.setName("内六角螺丝");
        line.setSpecification("M6×20");
        line.setUnit("个");
        line.setQuantity(new BigDecimal("40"));
        line.setPrice(new BigDecimal("2.64"));
        content.setLines(List.of(line));
        BillingDocument document = new BillingDocument();
        document.setId(9L);
        document.setOperatorId(7L);
        document.setStatus(status);
        document.setDocumentNumber(number);
        document.setContentJson(mapper.writeValueAsString(content));
        return document;
    }

    private FactoryLedgerTransaction transaction(Long ledgerId, long cents, String receiptNo, Long id, String status) {
        FactoryLedgerTransaction transaction = new FactoryLedgerTransaction();
        transaction.setId(id); transaction.setSource("BILLING");
        transaction.setLedgerId(ledgerId);
        transaction.setTransactionNo("LS-" + id);
        transaction.setReceiptNo(receiptNo);
        transaction.setStatus(status);
        transaction.setAmountCents(cents);
        transaction.setTransactionDate(LocalDate.parse("2026-10-21"));
        return transaction;
    }
}
