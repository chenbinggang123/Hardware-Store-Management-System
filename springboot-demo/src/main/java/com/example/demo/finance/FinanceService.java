package com.example.demo.finance;

import com.example.demo.entity.Customer;
import com.example.demo.entity.Supplier;
import com.example.demo.filecenter.entity.FileCenterActivity;
import com.example.demo.filecenter.repository.FileCenterActivityRepository;
import com.example.demo.filecenter.repository.FileCenterMetadataRepository;
import com.example.demo.finance.dto.AccountDocumentDetail;
import com.example.demo.finance.dto.AccountDocumentRequest;
import com.example.demo.finance.dto.AccountDocumentView;
import com.example.demo.finance.dto.AgingBucket;
import com.example.demo.finance.dto.CounterpartyPaymentRequest;
import com.example.demo.finance.dto.FinanceOverview;
import com.example.demo.finance.dto.PaymentAllocationResult;
import com.example.demo.finance.dto.PaymentRecordRequest;
import com.example.demo.finance.dto.PaymentView;
import com.example.demo.finance.entity.AccountDocument;
import com.example.demo.finance.entity.AccountPayment;
import com.example.demo.finance.entity.PaymentAllocation;
import com.example.demo.finance.repository.AccountDocumentRepository;
import com.example.demo.finance.repository.AccountPaymentRepository;
import com.example.demo.finance.repository.PaymentAllocationRepository;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class FinanceService {
    private static final Set<String> DIRECTIONS = Set.of("RECEIVABLE", "PAYABLE");
    private final AccountDocumentRepository documentRepository;
    private final AccountPaymentRepository paymentRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final FileCenterMetadataRepository fileMetadataRepository;
    private final FileCenterActivityRepository fileActivityRepository;

    public FinanceService(AccountDocumentRepository documentRepository,
                          AccountPaymentRepository paymentRepository,
                          PaymentAllocationRepository allocationRepository,
                          CustomerRepository customerRepository,
                          SupplierRepository supplierRepository,
                          FileCenterMetadataRepository fileMetadataRepository,
                          FileCenterActivityRepository fileActivityRepository) {
        this.documentRepository = documentRepository;
        this.paymentRepository = paymentRepository;
        this.allocationRepository = allocationRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
        this.fileMetadataRepository = fileMetadataRepository;
        this.fileActivityRepository = fileActivityRepository;
    }

    @Transactional(readOnly = true)
    public FinanceOverview overview(Long operatorId, String direction, String status, String keyword, YearMonth month) {
        requireOperator(operatorId);
        List<AccountDocument> all = documentRepository.findByOperatorIdOrderByDocumentDateDescIdDesc(operatorId);
        String wantedDirection = normalizeOptional(direction);
        if (wantedDirection != null && !DIRECTIONS.contains(wantedDirection)) throw new IllegalArgumentException("账款方向不正确");
        String wantedStatus = normalizeOptional(status);
        String wantedKeyword = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        List<AccountDocumentView> visible = all.stream()
                .filter(item -> wantedDirection == null || wantedDirection.equals(item.getDirection()))
                .filter(item -> wantedStatus == null || matchesStatus(item, wantedStatus))
                .filter(item -> wantedKeyword == null || searchable(item).contains(wantedKeyword))
                .map(this::view).toList();
        YearMonth selectedMonth = month == null ? YearMonth.now() : month;
        BigDecimal occurred = all.stream().filter(item -> selectedMonth.equals(YearMonth.from(item.getDocumentDate())))
                .map(AccountDocument::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal settled = paymentRepository.findByOperatorIdOrderByPaymentDateDescIdDesc(operatorId).stream()
                .filter(item -> selectedMonth.equals(YearMonth.from(item.getPaymentDate())))
                .map(AccountPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AccountDocument> open = all.stream()
                .filter(item -> !"SETTLED".equals(item.getStatus()))
                .filter(item -> wantedDirection == null || wantedDirection.equals(item.getDirection()))
                .toList();
        List<AccountDocument> overdue = open.stream().filter(this::isOverdue).toList();
        return new FinanceOverview(sumOpen(all, "RECEIVABLE"), sumOpen(all, "PAYABLE"), money(occurred), money(settled),
                open.size(), sumOutstanding(overdue), overdue.size(), aging(open), visible);
    }

    public AccountDocumentDetail create(Long operatorId, AccountDocumentRequest request) {
        requireOperator(operatorId);
        if (request == null) throw new IllegalArgumentException("账款资料不能为空");
        String direction = normalizeDirection(request.getDirection());
        Counterparty counterparty = counterparty(direction, request.getCounterpartyId());
        BigDecimal total = positive(request.getTotalAmount(), "单据金额必须大于 0");
        BigDecimal settled = request.getSettledAmount() == null ? BigDecimal.ZERO : money(request.getSettledAmount());
        if (settled.signum() < 0 || settled.compareTo(total) > 0) throw new IllegalArgumentException("已收付金额不能超过单据金额");
        LocalDateTime now = LocalDateTime.now();
        AccountDocument document = new AccountDocument();
        document.setOperatorId(operatorId);
        document.setDirection(direction);
        document.setCounterpartyType(counterparty.type());
        document.setCounterpartyId(counterparty.id());
        document.setCounterpartyName(counterparty.name());
        document.setDocumentNumber(clean(request.getDocumentNumber(), 100));
        document.setDocumentDate(request.getDocumentDate() == null ? LocalDate.now() : request.getDocumentDate());
        document.setDueDate(request.getDueDate());
        document.setTotalAmount(total);
        document.setSettledAmount(settled);
        document.setOutstandingAmount(money(total.subtract(settled)));
        document.setStatus(status(total, settled));
        document.setNote(clean(request.getNote(), 1000));
        document.setFileSourceKind(normalizeOptional(request.getFileSourceKind()));
        document.setFileSourceId(request.getFileSourceId());
        document.setCreateTime(now);
        document.setUpdateTime(now);
        document = documentRepository.save(document);
        if (settled.signum() > 0) createPayment(document, settled, document.getDocumentDate(), "期初已收付", "随单据登记", now);
        addFileActivity(operatorId, document);
        return detail(operatorId, document.getId());
    }

    @Transactional(readOnly = true)
    public AccountDocumentDetail detail(Long operatorId, Long id) {
        AccountDocument document = owned(operatorId, id);
        List<PaymentAllocation> allocations = allocationRepository.findByDocumentIdOrderByCreateTimeDesc(id);
        Map<Long, AccountPayment> payments = paymentRepository.findAllById(allocations.stream().map(PaymentAllocation::getPaymentId).toList())
                .stream().collect(Collectors.toMap(AccountPayment::getId, Function.identity()));
        List<PaymentView> paymentViews = allocations.stream()
                .filter(allocation -> payments.containsKey(allocation.getPaymentId()))
                .sorted(Comparator.comparing((PaymentAllocation allocation) -> payments.get(allocation.getPaymentId()).getPaymentDate()).reversed()
                        .thenComparing(PaymentAllocation::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(allocation -> {
                    AccountPayment item = payments.get(allocation.getPaymentId());
                    return new PaymentView(item.getId(), item.getPaymentDate(), allocation.getAmount(), item.getPaymentMethod(), item.getNote());
                })
                .toList();
        return new AccountDocumentDetail(view(document), paymentViews);
    }

    public AccountDocumentDetail recordPayment(Long operatorId, Long documentId, PaymentRecordRequest request) {
        if (request == null) throw new IllegalArgumentException("收付款资料不能为空");
        AccountDocument document = owned(operatorId, documentId);
        BigDecimal amount = positive(request.getAmount(), "收付款金额必须大于 0");
        if (amount.compareTo(document.getOutstandingAmount()) > 0) throw new IllegalArgumentException("收付款金额不能超过剩余欠款");
        LocalDateTime now = LocalDateTime.now();
        createPayment(document, amount, request.getPaymentDate() == null ? LocalDate.now() : request.getPaymentDate(),
                clean(request.getPaymentMethod(), 50), clean(request.getNote(), 500), now);
        BigDecimal settled = money(document.getSettledAmount().add(amount));
        document.setSettledAmount(settled);
        document.setOutstandingAmount(money(document.getTotalAmount().subtract(settled)));
        document.setStatus(status(document.getTotalAmount(), settled));
        document.setUpdateTime(now);
        documentRepository.save(document);
        return detail(operatorId, documentId);
    }

    public PaymentAllocationResult recordCounterpartyPayment(Long operatorId, CounterpartyPaymentRequest request) {
        requireOperator(operatorId);
        if (request == null) throw new IllegalArgumentException("收付款资料不能为空");
        String direction = normalizeDirection(request.getDirection());
        Counterparty counterparty = counterparty(direction, request.getCounterpartyId());
        BigDecimal amount = positive(request.getAmount(), "收付款金额必须大于 0");
        LocalDateTime now = LocalDateTime.now();
        AccountPayment payment = new AccountPayment();
        payment.setOperatorId(operatorId);
        payment.setDirection("RECEIVABLE".equals(direction) ? "RECEIVE" : "PAY");
        payment.setCounterpartyType(counterparty.type());
        payment.setCounterpartyId(counterparty.id());
        payment.setCounterpartyName(counterparty.name());
        payment.setPaymentDate(request.getPaymentDate() == null ? LocalDate.now() : request.getPaymentDate());
        payment.setAmount(amount);
        payment.setPaymentMethod(StringUtils.hasText(request.getPaymentMethod()) ? clean(request.getPaymentMethod(), 50) : "未注明");
        payment.setNote(clean(request.getNote(), 500));
        payment.setCreateTime(now);

        List<AccountDocument> openDocuments = documentRepository
                .findByOperatorIdAndDirectionAndCounterpartyTypeAndCounterpartyIdAndStatusNotOrderByDocumentDateAscIdAsc(
                        operatorId, direction, counterparty.type(), counterparty.id(), "SETTLED");
        BigDecimal remaining = amount;
        int count = 0;
        payment.setUnallocatedAmount(amount);
        payment = paymentRepository.save(payment);
        for (AccountDocument document : openDocuments) {
            if (remaining.signum() <= 0) break;
            BigDecimal allocated = remaining.min(money(document.getOutstandingAmount()));
            if (allocated.signum() <= 0) continue;
            addAllocation(payment.getId(), document.getId(), allocated, now);
            applySettlement(document, allocated, now);
            remaining = money(remaining.subtract(allocated));
            count++;
        }
        payment.setUnallocatedAmount(remaining);
        paymentRepository.save(payment);
        return new PaymentAllocationResult(payment.getId(), amount, money(amount.subtract(remaining)), remaining, count);
    }

    private void createPayment(AccountDocument document, BigDecimal amount, LocalDate date, String method, String note, LocalDateTime now) {
        AccountPayment payment = new AccountPayment();
        payment.setOperatorId(document.getOperatorId());
        payment.setDirection("RECEIVABLE".equals(document.getDirection()) ? "RECEIVE" : "PAY");
        payment.setCounterpartyType(document.getCounterpartyType());
        payment.setCounterpartyId(document.getCounterpartyId());
        payment.setCounterpartyName(document.getCounterpartyName());
        payment.setPaymentDate(date);
        payment.setAmount(amount);
        payment.setUnallocatedAmount(BigDecimal.ZERO.setScale(2));
        payment.setPaymentMethod(StringUtils.hasText(method) ? method : "未注明");
        payment.setNote(note);
        payment.setCreateTime(now);
        payment = paymentRepository.save(payment);
        addAllocation(payment.getId(), document.getId(), amount, now);
    }

    private void addAllocation(Long paymentId, Long documentId, BigDecimal amount, LocalDateTime now) {
        PaymentAllocation allocation = new PaymentAllocation();
        allocation.setPaymentId(paymentId);
        allocation.setDocumentId(documentId);
        allocation.setAmount(amount);
        allocation.setCreateTime(now);
        allocationRepository.save(allocation);
    }

    private void applySettlement(AccountDocument document, BigDecimal amount, LocalDateTime now) {
        BigDecimal settled = money(document.getSettledAmount().add(amount));
        document.setSettledAmount(settled);
        document.setOutstandingAmount(money(document.getTotalAmount().subtract(settled)));
        document.setStatus(status(document.getTotalAmount(), settled));
        document.setUpdateTime(now);
        documentRepository.save(document);
    }

    private Counterparty counterparty(String direction, Long id) {
        if (id == null) throw new IllegalArgumentException("请选择客户或厂商");
        if ("RECEIVABLE".equals(direction)) {
            Customer item = customerRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("客户不存在"));
            return new Counterparty("CUSTOMER", id, item.getName());
        }
        Supplier item = supplierRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("厂商不存在"));
        return new Counterparty("SUPPLIER", id, item.getName());
    }

    private void addFileActivity(Long operatorId, AccountDocument document) {
        if (!StringUtils.hasText(document.getFileSourceKind()) || document.getFileSourceId() == null) return;
        fileMetadataRepository.findByOperatorIdAndSourceKindAndSourceId(operatorId, document.getFileSourceKind(), document.getFileSourceId())
                .ifPresent(metadata -> {
                    FileCenterActivity activity = new FileCenterActivity();
                    activity.setMetadataId(metadata.getId());
                    activity.setActivityType("ACCOUNT_CREATED");
                    activity.setTitle("已生成往来账");
                    activity.setDetail(document.getCounterpartyName() + " · ¥" + document.getTotalAmount());
                    activity.setCreateTime(LocalDateTime.now());
                    fileActivityRepository.save(activity);
                });
    }

    private AccountDocument owned(Long operatorId, Long id) {
        requireOperator(operatorId);
        return documentRepository.findByIdAndOperatorId(id, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("账款单据不存在或无权访问"));
    }
    private AccountDocumentView view(AccountDocument item) { return new AccountDocumentView(item.getId(), item.getDirection(), item.getCounterpartyType(), item.getCounterpartyId(), item.getCounterpartyName(), item.getDocumentNumber(), item.getDocumentDate(), item.getDueDate(), item.getTotalAmount(), item.getSettledAmount(), item.getOutstandingAmount(), item.getStatus(), item.getNote(), item.getFileSourceKind(), item.getFileSourceId(), item.getBusinessSourceType(), item.getBusinessSourceId(), item.getUpdateTime()); }
    private BigDecimal sumOpen(List<AccountDocument> items, String direction) { return money(items.stream().filter(item -> direction.equals(item.getDirection())).map(AccountDocument::getOutstandingAmount).reduce(BigDecimal.ZERO, BigDecimal::add)); }
    private BigDecimal sumOutstanding(List<AccountDocument> items) { return money(items.stream().map(AccountDocument::getOutstandingAmount).reduce(BigDecimal.ZERO, BigDecimal::add)); }
    private boolean matchesStatus(AccountDocument item, String wanted) {
        if ("OPEN".equals(wanted)) return !"SETTLED".equals(item.getStatus());
        if ("OVERDUE".equals(wanted)) return isOverdue(item);
        return wanted.equals(item.getStatus());
    }
    private boolean isOverdue(AccountDocument item) {
        return !"SETTLED".equals(item.getStatus()) && item.getDueDate() != null && item.getDueDate().isBefore(LocalDate.now());
    }
    private List<AgingBucket> aging(List<AccountDocument> items) {
        List<AccountDocument> current = new ArrayList<>();
        List<AccountDocument> days31 = new ArrayList<>();
        List<AccountDocument> days61 = new ArrayList<>();
        List<AccountDocument> days91 = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (AccountDocument item : items) {
            long days = Math.max(0, ChronoUnit.DAYS.between(item.getDocumentDate(), today));
            if (days <= 30) current.add(item);
            else if (days <= 60) days31.add(item);
            else if (days <= 90) days61.add(item);
            else days91.add(item);
        }
        return List.of(bucket("DAYS_0_30", "0–30 天", current), bucket("DAYS_31_60", "31–60 天", days31),
                bucket("DAYS_61_90", "61–90 天", days61), bucket("DAYS_91_PLUS", "90 天以上", days91));
    }
    private AgingBucket bucket(String code, String label, List<AccountDocument> items) {
        return new AgingBucket(code, label, items.size(), sumOutstanding(items));
    }
    private String searchable(AccountDocument item) { return (safe(item.getCounterpartyName()) + " " + safe(item.getDocumentNumber()) + " " + safe(item.getNote())).toLowerCase(Locale.ROOT); }
    private String normalizeDirection(String value) { String result = normalizeOptional(value); if (!DIRECTIONS.contains(result)) throw new IllegalArgumentException("账款方向不正确"); return result; }
    private String normalizeOptional(String value) { return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null; }
    private BigDecimal positive(BigDecimal value, String message) { if (value == null || value.signum() <= 0) throw new IllegalArgumentException(message); return money(value); }
    private BigDecimal money(BigDecimal value) { return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP); }
    private String status(BigDecimal total, BigDecimal settled) { return settled.signum() == 0 ? "UNPAID" : settled.compareTo(total) >= 0 ? "SETTLED" : "PARTIAL"; }
    private String clean(String value, int max) { if (!StringUtils.hasText(value)) return null; String result = value.trim(); return result.length() <= max ? result : result.substring(0, max); }
    private String safe(String value) { return value == null ? "" : value; }
    private void requireOperator(Long operatorId) { if (operatorId == null) throw new IllegalArgumentException("缺少操作人"); }
    private record Counterparty(String type, Long id, String name) {}
}
