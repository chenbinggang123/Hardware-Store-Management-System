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
        BillingDocument document = document("CONFIRMED", "KD-20261021-9", 3L);
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(document));
        FactoryLedgerTransaction saved = transaction(3L, 10560L);
        when(transactions.findFirstByReceiptNoAndSource("KD-20261021-9", "BILLING"))
                .thenReturn(Optional.empty(), Optional.of(saved));

        Map<String, Object> result = service.post(7L, 9L, null);

        ArgumentCaptor<FactoryLedgerTransactionRequest> request = ArgumentCaptor.forClass(FactoryLedgerTransactionRequest.class);
        verify(ledgers).addBillingTransaction(eq(3L), request.capture(), eq(7L));
        assertThat(request.getValue().getType()).isEqualTo("PURCHASE");
        assertThat(request.getValue().getReceiptNo()).isEqualTo("KD-20261021-9");
        assertThat(request.getValue().getDate()).isEqualTo(LocalDate.parse("2026-10-21"));
        assertThat(request.getValue().getItems()).hasSize(1);
        assertThat(request.getValue().getItems().get(0).getUnitPrice()).isEqualByComparingTo("2.64");
        assertThat(result.get("posted")).isEqualTo(true);
        assertThat(result.get("factoryName")).isEqualTo("恒达模具厂");
        assertThat((BigDecimal) result.get("amount")).isEqualByComparingTo("105.60");
    }

    @Test
    void postingTwiceDoesNotCreateASecondEntry() throws Exception {
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(document("CONFIRMED", "KD-20261021-9", 3L)));
        when(transactions.findFirstByReceiptNoAndSource("KD-20261021-9", "BILLING"))
                .thenReturn(Optional.of(transaction(3L, 10560L)));

        assertThat(service.post(7L, 9L, 3L).get("posted")).isEqualTo(true);
        assertThatThrownBy(() -> service.post(7L, 9L, 4L)).hasMessageContaining("恒达模具厂");
        verify(ledgers, never()).addBillingTransaction(any(), any(), any());
    }

    @Test
    void voidingAPostedDocumentReversesTheLedgerEntry() throws Exception {
        BillingDocument document = document("CONFIRMED", "KD-20261021-9", 3L);
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(document));
        FactoryLedgerTransaction posted = transaction(3L, 10560L);
        posted.setId(55L);
        posted.setStatus("ACTIVE");
        when(transactions.findFirstByReceiptNoAndSource("KD-20261021-9", "BILLING")).thenReturn(Optional.of(posted));

        service.voidDocument(7L, 9L, "客户退单");

        verify(ledgers).reverseTransaction(eq(3L), eq(55L), org.mockito.ArgumentMatchers.contains("客户退单"), eq(7L));
        assertThat(document.getStatus()).isEqualTo("VOIDED");
        assertThatThrownBy(() -> service.post(7L, 9L, 3L)).hasMessageContaining("先保存单据");
    }

    @Test
    void draftsAndDocumentsWithoutLedgerAreRejected() throws Exception {
        when(documents.lockOwned(9L, 7L)).thenReturn(Optional.of(document("DRAFT", null, 3L)));
        assertThatThrownBy(() -> service.post(7L, 9L, 3L)).hasMessageContaining("先保存单据");

        when(documents.lockOwned(10L, 7L)).thenReturn(Optional.of(document("CONFIRMED", "KD-20261021-10", null)));
        when(transactions.findFirstByReceiptNoAndSource("KD-20261021-10", "BILLING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.post(7L, 10L, null)).hasMessageContaining("选择");
        verify(ledgers, never()).addBillingTransaction(any(), any(), any());
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

    private FactoryLedgerTransaction transaction(Long ledgerId, long cents) {
        FactoryLedgerTransaction transaction = new FactoryLedgerTransaction();
        transaction.setLedgerId(ledgerId);
        transaction.setTransactionNo("LS-1");
        transaction.setAmountCents(cents);
        transaction.setTransactionDate(LocalDate.parse("2026-10-21"));
        return transaction;
    }
}
