package com.example.demo.billing;

import com.example.demo.service.FactoryLedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 月度汇总：一页看完这个月开了多少单、各厂拿了多少货、收了多少钱、还欠多少。
 * 数据只来自快捷开单和工厂账本，和小程序日常使用的两套功能一致。
 */
@Service
@Transactional(readOnly = true)
public class MonthlySummaryService {
    private final BillingDocumentRepository documents;
    private final FactoryLedgerService ledgers;

    public MonthlySummaryService(BillingDocumentRepository documents, FactoryLedgerService ledgers) {
        this.documents = documents;
        this.ledgers = ledgers;
    }

    public Map<String, Object> summary(String monthText) {
        YearMonth month = parseMonth(monthText);
        String prefix = "KD-" + month.toString().replace("-", "");
        List<BillingDocument> billed = documents.findByStatusInAndDocumentNumberStartingWith(Set.of("CONFIRMED", "VOIDED"), prefix);
        BigDecimal billedAmount = BigDecimal.ZERO;
        int billedCount = 0;
        int voidedCount = 0;
        for (BillingDocument document : billed) {
            if (StringUtils.hasText(document.getContentJson())) {
                try { if ("PROCUREMENT".equals(new com.fasterxml.jackson.databind.ObjectMapper().readTree(document.getContentJson()).path("documentType").asText())) continue; }
                catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("汇总单据内容无法读取", e); }
            }
            if ("VOIDED".equals(document.getStatus())) {
                voidedCount++;
                continue;
            }
            billedCount++;
            if (document.getTotalAmount() != null) {
                boolean returned = false;
                if (StringUtils.hasText(document.getContentJson())) {
                    try { returned = "RETURN".equals(new com.fasterxml.jackson.databind.ObjectMapper().readTree(document.getContentJson()).path("documentType").asText()); }
                    catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("汇总单据内容无法读取", e); }
                }
                billedAmount = returned ? billedAmount.subtract(document.getTotalAmount()) : billedAmount.add(document.getTotalAmount());
            }
        }

        Map<String, Object> ledgerList = ledgers.list(month.toString(), null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ledgerList.getOrDefault("ledgers", List.of());
        long purchase = 0, returned = 0, payment = 0, balance = 0;
        List<Map<String, Object>> factories = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            purchase += number(row.get("purchaseCents"));
            returned += number(row.get("returnCents"));
            payment += number(row.get("paymentCents"));
            balance += number(row.get("balanceCents"));
            Map<String, Object> factory = new LinkedHashMap<>();
            factory.put("id", row.get("id"));
            factory.put("factoryName", row.get("factoryName"));
            factory.put("purchaseAmount", row.get("purchaseAmount"));
            factory.put("returnAmount", row.get("returnAmount"));
            factory.put("paymentAmount", row.get("paymentAmount"));
            factory.put("balanceAmount", row.get("balanceAmount"));
            factory.put("balanceCents", number(row.get("balanceCents")));
            factory.put("statusLabel", row.get("statusLabel"));
            factories.add(factory);
        }
        factories.sort(Comparator.comparingLong((Map<String, Object> row) -> number(row.get("balanceCents"))).reversed());

        Map<String, Object> billing = new LinkedHashMap<>();
        billing.put("count", billedCount);
        billing.put("amount", billedAmount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        billing.put("voidedCount", voidedCount);

        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("factoryCount", factories.size());
        ledger.put("purchaseAmount", yuan(purchase));
        ledger.put("returnAmount", yuan(returned));
        ledger.put("paymentAmount", yuan(payment));
        ledger.put("balanceAmount", yuan(balance));
        ledger.put("totalDueAmount", yuan(rows.stream().mapToLong(row -> number(row.get("totalDueCents"))).sum()));
        ledger.put("owingCount", factories.stream().filter(row -> number(row.get("balanceCents")) > 0).count());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", month.toString());
        result.put("billing", billing);
        result.put("ledger", ledger);
        result.put("factories", factories);
        return result;
    }

    private static YearMonth parseMonth(String value) {
        try {
            return StringUtils.hasText(value) ? YearMonth.parse(value.trim()) : YearMonth.now();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("月份格式错误，请使用 YYYY-MM");
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static String yuan(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
