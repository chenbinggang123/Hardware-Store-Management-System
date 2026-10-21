package com.example.demo.billing;

import com.example.demo.service.FactoryLedgerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonthlySummaryServiceTest {

    @Test
    void addsUpBillsAndFactoryLedgersForTheMonth() {
        BillingDocumentRepository documents = mock(BillingDocumentRepository.class);
        FactoryLedgerService ledgers = mock(FactoryLedgerService.class);
        when(documents.findByStatusInAndDocumentNumberStartingWith(any(), eq("KD-202610")))
                .thenReturn(List.of(document("CONFIRMED", "105.60"), document("CONFIRMED", "20.00"), document("VOIDED", "50.00")));
        when(ledgers.list("2026-10", null)).thenReturn(Map.of("ledgers", List.of(
                Map.of("id", 1L, "factoryName", "甲厂", "purchaseCents", 10560L, "returnCents", 0L, "paymentCents", 5000L,
                        "balanceCents", 18410L, "purchaseAmount", "105.60", "balanceAmount", "184.10", "statusLabel", "未对账"),
                Map.of("id", 2L, "factoryName", "乙厂", "purchaseCents", 0L, "returnCents", 0L, "paymentCents", 0L,
                        "balanceCents", 0L, "purchaseAmount", "0.00", "balanceAmount", "0.00", "statusLabel", "已结清")
        )));

        Map<String, Object> summary = new MonthlySummaryService(documents, ledgers).summary("2026-10");

        Map<?, ?> billing = (Map<?, ?>) summary.get("billing");
        Map<?, ?> ledger = (Map<?, ?>) summary.get("ledger");
        assertThat(billing.get("count")).isEqualTo(2);
        assertThat(billing.get("amount")).isEqualTo("125.60");
        assertThat(billing.get("voidedCount")).isEqualTo(1);
        assertThat(ledger.get("balanceAmount")).isEqualTo("184.10");
        assertThat(ledger.get("paymentAmount")).isEqualTo("50.00");
        assertThat(ledger.get("owingCount")).isEqualTo(1L);
        assertThat(((Map<?, ?>) ((List<?>) summary.get("factories")).get(0)).get("factoryName")).isEqualTo("甲厂");
    }

    private BillingDocument document(String status, String total) {
        BillingDocument document = new BillingDocument();
        document.setStatus(status);
        document.setTotalAmount(new BigDecimal(total));
        return document;
    }
}
