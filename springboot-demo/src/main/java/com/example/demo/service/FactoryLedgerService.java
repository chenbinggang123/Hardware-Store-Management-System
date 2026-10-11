package com.example.demo.service;

import com.example.demo.dto.FactoryLedgerCreateRequest;
import com.example.demo.dto.FactoryLedgerTransactionRequest;
import com.example.demo.entity.FactoryLedger;
import com.example.demo.entity.FactoryLedgerItem;
import com.example.demo.entity.FactoryLedgerSettlement;
import com.example.demo.entity.FactoryLedgerTransaction;
import com.example.demo.entity.OperationLog;
import com.example.demo.repository.FactoryLedgerRepository;
import com.example.demo.repository.FactoryLedgerSettlementRepository;
import com.example.demo.repository.FactoryLedgerTransactionRepository;
import com.example.demo.repository.OperationLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class FactoryLedgerService {
    private static final Set<String> TRANSACTION_TYPES = Set.of("PURCHASE", "RETURN", "PAYMENT");
    private static final Set<String> SETTLEMENT_STATUSES = Set.of(
            "PENDING_SUMMARY", "PENDING_CHECK", "CONFIRMED", "INVOICED", "PARTIAL_PAID", "PAID", "CLOSED");

    private final FactoryLedgerRepository ledgerRepository;
    private final FactoryLedgerTransactionRepository transactionRepository;
    private final FactoryLedgerSettlementRepository settlementRepository;
    private final OperationLogRepository operationLogRepository;

    public FactoryLedgerService(
            FactoryLedgerRepository ledgerRepository,
            FactoryLedgerTransactionRepository transactionRepository,
            FactoryLedgerSettlementRepository settlementRepository,
            OperationLogRepository operationLogRepository) {
        this.ledgerRepository = ledgerRepository;
        this.transactionRepository = transactionRepository;
        this.settlementRepository = settlementRepository;
        this.operationLogRepository = operationLogRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(String monthText, String keyword) {
        YearMonth month = parseMonth(monthText);
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : "";
        List<Map<String, Object>> rows = ledgerRepository.findAll().stream()
                .filter(ledger -> matchesKeyword(ledger, normalizedKeyword))
                .sorted(Comparator.comparing(FactoryLedger::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ledger -> summaryView(ledger, month))
                .toList();

        long purchaseCents = rows.stream().mapToLong(row -> number(row.get("purchaseCents"))).sum();
        long balanceCents = rows.stream().mapToLong(row -> number(row.get("balanceCents"))).sum();
        long pendingCount = rows.stream().filter(row -> !Set.of("PAID", "CLOSED").contains(row.get("status"))).count();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("factoryCount", rows.size());
        totals.put("purchaseAmount", yuan(purchaseCents));
        totals.put("balanceAmount", yuan(balanceCents));
        totals.put("pendingCount", pendingCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", month.toString());
        result.put("ledgers", rows);
        result.put("totals", totals);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long ledgerId, String monthText) {
        FactoryLedger ledger = requireLedger(ledgerId);
        YearMonth month = parseMonth(monthText);
        List<FactoryLedgerTransaction> allTransactions = transactionRepository
                .findByLedgerIdOrderByTransactionDateDescCreateTimeDesc(ledgerId);
        Map<String, Object> result = ledgerView(ledger);
        result.put("month", month.toString());
        result.put("summary", calculateSummary(ledger, allTransactions, month));
        result.put("transactions", allTransactions.stream()
                .filter(transaction -> YearMonth.from(transaction.getTransactionDate()).equals(month))
                .map(this::transactionView)
                .toList());
        return result;
    }

    public Map<String, Object> create(FactoryLedgerCreateRequest request, Long operatorId) {
        if (request == null || !StringUtils.hasText(request.getFactoryName())) {
            throw new IllegalArgumentException("请填写工厂名称");
        }
        String factoryName = request.getFactoryName().trim();
        if (ledgerRepository.findByFactoryNameIgnoreCase(factoryName).isPresent()) {
            throw new IllegalArgumentException("这家工厂已经有账本");
        }
        int settlementDay = request.getSettlementDay() == null ? 31 : request.getSettlementDay();
        if (settlementDay < 1 || settlementDay > 31) {
            throw new IllegalArgumentException("结账日必须在 1 到 31 之间");
        }
        LocalDateTime now = LocalDateTime.now();
        FactoryLedger ledger = new FactoryLedger();
        ledger.setLedgerNo(readableNumber("ZB"));
        ledger.setFactoryName(factoryName);
        ledger.setContactPerson(trim(request.getContactPerson()));
        ledger.setPhone(trim(request.getPhone()));
        ledger.setStartDate(request.getStartDate() == null ? LocalDate.now() : request.getStartDate());
        ledger.setSettlementDay(settlementDay);
        ledger.setOpeningBalanceCents(toCents(request.getOpeningBalance()));
        ledger.setRemark(trim(request.getRemark()));
        ledger.setStatus("ACTIVE");
        ledger.setCreateTime(now);
        ledger.setUpdateTime(now);
        FactoryLedger saved = ledgerRepository.save(ledger);
        audit(operatorId, "CREATE", "新建工厂账本：" + saved.getFactoryName());
        return ledgerView(saved);
    }

    public Map<String, Object> addTransaction(Long ledgerId, FactoryLedgerTransactionRequest request, Long operatorId) {
        return addTransaction(ledgerId, request, operatorId, "MINIPROGRAM");
    }

    public Map<String, Object> addAgentTransaction(Long ledgerId, FactoryLedgerTransactionRequest request, Long operatorId) {
        return addTransaction(ledgerId, request, operatorId, "AGENT");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> previewTransaction(Long ledgerId, FactoryLedgerTransactionRequest request) {
        PreparedTransaction prepared = prepareTransaction(ledgerId, request);
        List<FactoryLedgerTransaction> currentTransactions = transactionRepository
                .findByLedgerIdOrderByTransactionDateDescCreateTimeDesc(ledgerId);
        YearMonth month = YearMonth.from(prepared.date());
        Map<String, Object> currentSummary = calculateSummary(prepared.ledger(), currentTransactions, month);
        long balanceBeforeCents = number(currentSummary.get("balanceCents"));
        long balanceAfterCents = balanceBeforeCents + transactionDelta(request.getType(), prepared.amountCents());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ledgerId", ledgerId);
        result.put("ledgerNo", prepared.ledger().getLedgerNo());
        result.put("factoryName", prepared.ledger().getFactoryName());
        result.put("type", request.getType());
        result.put("typeLabel", transactionTypeLabel(request.getType()));
        result.put("date", prepared.date());
        result.put("receiptNo", prepared.receiptNo());
        result.put("items", prepared.items());
        result.put("amountCents", prepared.amountCents());
        result.put("amount", yuan(prepared.amountCents()));
        result.put("balanceBeforeCents", balanceBeforeCents);
        result.put("balanceBefore", yuan(balanceBeforeCents));
        result.put("balanceAfterCents", balanceAfterCents);
        result.put("balanceAfter", yuan(balanceAfterCents));
        result.put("remark", trim(request.getRemark()));
        result.put("effect", transactionTypeLabel(request.getType()) + " ¥" + yuan(prepared.amountCents())
                + "，账本余额将从 ¥" + yuan(balanceBeforeCents) + " 变为 ¥" + yuan(balanceAfterCents));
        return result;
    }

    private Map<String, Object> addTransaction(
            Long ledgerId,
            FactoryLedgerTransactionRequest request,
            Long operatorId,
            String source) {
        PreparedTransaction prepared = prepareTransaction(ledgerId, request);
        FactoryLedger ledger = prepared.ledger();
        List<FactoryLedgerItem> items = prepared.items();
        long amountCents = prepared.amountCents();
        String receiptNo = prepared.receiptNo();

        LocalDateTime now = LocalDateTime.now();
        FactoryLedgerTransaction transaction = new FactoryLedgerTransaction();
        transaction.setTransactionNo(readableNumber("LS"));
        transaction.setLedgerId(ledgerId);
        transaction.setType(request.getType());
        transaction.setTransactionDate(prepared.date());
        transaction.setReceiptNo(receiptNo);
        transaction.setAmountCents(amountCents);
        transaction.setItems(items);
        transaction.setRemark(trim(request.getRemark()));
        transaction.setSource(source);
        transaction.setOperatorId(operatorId);
        transaction.setCreateTime(now);
        FactoryLedgerTransaction saved = transactionRepository.save(transaction);

        ledger.setUpdateTime(now);
        ledgerRepository.save(ledger);
        upsertSettlement(ledgerId, YearMonth.from(saved.getTransactionDate()).toString(), "PENDING_SUMMARY", operatorId);
        audit(operatorId, request.getType(), transactionTypeLabel(request.getType()) + "：" + ledger.getFactoryName() + " ¥" + yuan(amountCents));
        return transactionView(saved);
    }

    private PreparedTransaction prepareTransaction(Long ledgerId, FactoryLedgerTransactionRequest request) {
        FactoryLedger ledger = requireLedger(ledgerId);
        if (request == null || !TRANSACTION_TYPES.contains(request.getType())) {
            throw new IllegalArgumentException("请选择拿货、退货或收款");
        }
        String receiptNo = trim(request.getReceiptNo());
        if (StringUtils.hasText(receiptNo) && transactionRepository.existsByLedgerIdAndReceiptNo(ledgerId, receiptNo)) {
            throw new IllegalArgumentException("这张收据已经记过账");
        }

        List<FactoryLedgerItem> items = normalizeItems(request.getItems());
        long amountCents;
        if ("PAYMENT".equals(request.getType())) {
            amountCents = toCents(request.getPaymentAmount());
        } else {
            amountCents = items.stream().mapToLong(item -> item.getQuantity()
                    .multiply(BigDecimal.valueOf(item.getUnitPriceCents()))
                    .setScale(0, RoundingMode.HALF_UP).longValue()).sum();
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("PAYMENT".equals(request.getType()) ? "请填写收款金额" : "请至少填写一件有效商品");
        }
        return new PreparedTransaction(
                ledger,
                items,
                amountCents,
                receiptNo,
                request.getDate() == null ? LocalDate.now() : request.getDate());
    }

    public Map<String, Object> setSettlementStatus(Long ledgerId, String monthText, String status, Long operatorId) {
        requireLedger(ledgerId);
        YearMonth month = parseMonth(monthText);
        if (!SETTLEMENT_STATUSES.contains(status)) {
            throw new IllegalArgumentException("无效的结账状态");
        }
        FactoryLedgerSettlement settlement = upsertSettlement(ledgerId, month.toString(), status, operatorId);
        audit(operatorId, "SETTLEMENT", "更新 " + month + " 结账进度为 " + status);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ledgerId", ledgerId);
        result.put("month", month.toString());
        result.put("status", settlement.getStatus());
        result.put("statusLabel", settlementStatusLabel(settlement.getStatus()));
        return result;
    }

    private Map<String, Object> summaryView(FactoryLedger ledger, YearMonth month) {
        Map<String, Object> result = ledgerView(ledger);
        result.putAll(calculateSummary(ledger,
                transactionRepository.findByLedgerIdOrderByTransactionDateDescCreateTimeDesc(ledger.getId()), month));
        return result;
    }

    private Map<String, Object> calculateSummary(FactoryLedger ledger, List<FactoryLedgerTransaction> transactions, YearMonth month) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        long openingCents = ledger.getOpeningBalanceCents() == null ? 0 : ledger.getOpeningBalanceCents();
        long purchaseCents = 0;
        long returnCents = 0;
        long paymentCents = 0;
        int purchaseCount = 0;
        int transactionCount = 0;
        for (FactoryLedgerTransaction transaction : transactions) {
            LocalDate date = transaction.getTransactionDate();
            if (date.isBefore(monthStart)) {
                openingCents += transactionDelta(transaction);
                continue;
            }
            if (date.isAfter(monthEnd)) continue;
            transactionCount++;
            switch (transaction.getType()) {
                case "PURCHASE" -> { purchaseCents += transaction.getAmountCents(); purchaseCount++; }
                case "RETURN" -> returnCents += transaction.getAmountCents();
                case "PAYMENT" -> paymentCents += transaction.getAmountCents();
                default -> { }
            }
        }
        long balanceCents = openingCents + purchaseCents - returnCents - paymentCents;
        String status = settlementRepository.findByLedgerIdAndSettlementMonth(ledger.getId(), month.toString())
                .map(FactoryLedgerSettlement::getStatus).orElse("PENDING_SUMMARY");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openingCents", openingCents);
        result.put("purchaseCents", purchaseCents);
        result.put("returnCents", returnCents);
        result.put("paymentCents", paymentCents);
        result.put("balanceCents", balanceCents);
        result.put("openingAmount", yuan(openingCents));
        result.put("purchaseAmount", yuan(purchaseCents));
        result.put("returnAmount", yuan(returnCents));
        result.put("paymentAmount", yuan(paymentCents));
        result.put("balanceAmount", yuan(balanceCents));
        result.put("purchaseCount", purchaseCount);
        result.put("transactionCount", transactionCount);
        result.put("status", status);
        result.put("statusLabel", settlementStatusLabel(status));
        return result;
    }

    private Map<String, Object> ledgerView(FactoryLedger ledger) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", ledger.getId());
        result.put("ledgerNo", ledger.getLedgerNo());
        result.put("factoryName", ledger.getFactoryName());
        result.put("contactPerson", ledger.getContactPerson());
        result.put("phone", ledger.getPhone());
        result.put("startDate", ledger.getStartDate());
        result.put("settlementDay", ledger.getSettlementDay());
        result.put("openingBalanceCents", ledger.getOpeningBalanceCents());
        result.put("remark", ledger.getRemark());
        result.put("ledgerStatus", ledger.getStatus());
        result.put("createdAt", ledger.getCreateTime());
        result.put("updatedAt", ledger.getUpdateTime());
        return result;
    }

    private Map<String, Object> transactionView(FactoryLedgerTransaction transaction) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", transaction.getId());
        result.put("transactionNo", transaction.getTransactionNo());
        result.put("ledgerId", transaction.getLedgerId());
        result.put("type", transaction.getType());
        result.put("typeLabel", transactionTypeLabel(transaction.getType()));
        result.put("date", transaction.getTransactionDate());
        result.put("receiptNo", transaction.getReceiptNo());
        result.put("items", transaction.getItems());
        result.put("amountCents", transaction.getAmountCents());
        result.put("amount", yuan(transaction.getAmountCents()));
        result.put("signedAmount", ("PURCHASE".equals(transaction.getType()) ? "+" : "-") + "¥" + yuan(transaction.getAmountCents()));
        result.put("content", transactionContent(transaction));
        result.put("remark", transaction.getRemark());
        result.put("source", transaction.getSource());
        result.put("createdAt", transaction.getCreateTime());
        return result;
    }

    private List<FactoryLedgerItem> normalizeItems(List<FactoryLedgerTransactionRequest.Item> requests) {
        if (requests == null) return List.of();
        List<FactoryLedgerItem> items = new ArrayList<>();
        for (FactoryLedgerTransactionRequest.Item request : requests) {
            if (request == null || !StringUtils.hasText(request.getProductName())
                    || request.getQuantity() == null || request.getQuantity().compareTo(BigDecimal.ZERO) <= 0
                    || request.getUnitPrice() == null || request.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) continue;
            FactoryLedgerItem item = new FactoryLedgerItem();
            item.setProductName(request.getProductName().trim());
            item.setSpecification(trim(request.getSpecification()));
            item.setUnit(StringUtils.hasText(request.getUnit()) ? request.getUnit().trim() : "件");
            item.setQuantity(request.getQuantity());
            item.setUnitPriceCents(toCents(request.getUnitPrice()));
            items.add(item);
        }
        return items;
    }

    private FactoryLedgerSettlement upsertSettlement(Long ledgerId, String month, String status, Long operatorId) {
        FactoryLedgerSettlement settlement = settlementRepository.findByLedgerIdAndSettlementMonth(ledgerId, month)
                .orElseGet(FactoryLedgerSettlement::new);
        settlement.setLedgerId(ledgerId);
        settlement.setSettlementMonth(month);
        settlement.setStatus(status);
        settlement.setOperatorId(operatorId);
        settlement.setUpdateTime(LocalDateTime.now());
        return settlementRepository.save(settlement);
    }

    private FactoryLedger requireLedger(Long ledgerId) {
        return ledgerRepository.findById(ledgerId)
                .orElseThrow(() -> new IllegalArgumentException("工厂账本不存在"));
    }

    private YearMonth parseMonth(String value) {
        try {
            return StringUtils.hasText(value) ? YearMonth.parse(value) : YearMonth.now();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("月份格式错误，请使用 YYYY-MM");
        }
    }

    private boolean matchesKeyword(FactoryLedger ledger, String keyword) {
        if (!StringUtils.hasText(keyword)) return true;
        return contains(ledger.getFactoryName(), keyword) || contains(ledger.getContactPerson(), keyword)
                || contains(ledger.getPhone(), keyword) || contains(ledger.getLedgerNo(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private long transactionDelta(FactoryLedgerTransaction transaction) {
        return transactionDelta(transaction.getType(), transaction.getAmountCents());
    }

    private long transactionDelta(String type, long amountCents) {
        return "PURCHASE".equals(type) ? amountCents : -amountCents;
    }

    private String transactionContent(FactoryLedgerTransaction transaction) {
        if ("PAYMENT".equals(transaction.getType())) return "登记收款";
        return transaction.getItems().stream()
                .map(item -> item.getProductName()
                        + (StringUtils.hasText(item.getSpecification()) ? " " + item.getSpecification() : "")
                        + " × " + item.getQuantity().stripTrailingZeros().toPlainString())
                .reduce((left, right) -> left + "；" + right).orElse("商品明细");
    }

    private String transactionTypeLabel(String type) {
        return switch (type) { case "PURCHASE" -> "拿货"; case "RETURN" -> "退货"; case "PAYMENT" -> "收款"; default -> "调整"; };
    }

    private String settlementStatusLabel(String status) {
        return switch (status) {
            case "PENDING_CHECK" -> "待核对"; case "CONFIRMED" -> "已确认"; case "INVOICED" -> "已开单";
            case "PARTIAL_PAID" -> "部分付款"; case "PAID" -> "已付款"; case "CLOSED" -> "已结清"; default -> "待汇总";
        };
    }

    private long toCents(BigDecimal value) {
        if (value == null) return 0;
        return value.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private String yuan(long cents) {
        return BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private String readableNumber(String prefix) {
        return prefix + "-" + LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void audit(Long operatorId, String action, String detail) {
        OperationLog log = new OperationLog();
        log.setOperatorId(operatorId);
        log.setModule("FACTORY_LEDGER");
        log.setAction(action);
        log.setDetail(detail);
        log.setCreateTime(LocalDateTime.now());
        operationLogRepository.save(log);
    }

    private record PreparedTransaction(
            FactoryLedger ledger,
            List<FactoryLedgerItem> items,
            long amountCents,
            String receiptNo,
            LocalDate date) {
    }
}
