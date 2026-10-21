package com.example.demo.billing;

import com.example.demo.dto.FactoryLedgerTransactionRequest;
import com.example.demo.entity.FactoryLedger;
import com.example.demo.entity.FactoryLedgerTransaction;
import com.example.demo.repository.FactoryLedgerRepository;
import com.example.demo.repository.FactoryLedgerTransactionRepository;
import com.example.demo.service.FactoryLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把已保存的销售单记入厂家账本，记为该厂的一笔“拿货”。
 *
 * <p>单据号作为账本收据号，并按来源 BILLING 查询，所以同一张单据只会记一次账；
 * 草稿不能记账，记账前单据内容必须已经保存定稿。</p>
 */
@Service
@Transactional
public class BillingLedgerService {
    static final String SOURCE = "BILLING";

    private final BillingDocumentRepository documents;
    private final FactoryLedgerService ledgers;
    private final FactoryLedgerRepository ledgerRepository;
    private final FactoryLedgerTransactionRepository transactions;
    private final ObjectMapper mapper;

    public BillingLedgerService(BillingDocumentRepository documents, FactoryLedgerService ledgers,
                                FactoryLedgerRepository ledgerRepository,
                                FactoryLedgerTransactionRepository transactions, ObjectMapper mapper) {
        this.documents = documents;
        this.ledgers = ledgers;
        this.ledgerRepository = ledgerRepository;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(Long owner, Long documentId) {
        BillingDocument document = documents.findById(documentId)
                .filter(found -> owner != null && owner.equals(found.getOperatorId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "单据不存在"));
        return view(posted(document));
    }

    /** 开单记录：按最近修改排序，可按单号、客户、商品搜索，并标出是否已记入厂家账本。 */
    @Transactional(readOnly = true)
    public Map<String, Object> records(Long owner, String keyword, int page, int size) {
        if (owner == null || owner <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        String text = keyword == null ? "" : keyword.trim();
        if (text.length() > 40) text = text.substring(0, 40);
        int pageSize = Math.min(Math.max(size, 1), 50);
        org.springframework.data.domain.Page<BillingDocument> found = documents.search(owner, text,
                org.springframework.data.domain.PageRequest.of(Math.max(page, 0), pageSize));
        List<String> numbers = found.getContent().stream().map(BillingDocument::getDocumentNumber)
                .filter(StringUtils::hasText).toList();
        Map<String, FactoryLedgerTransaction> posted = new java.util.HashMap<>();
        if (!numbers.isEmpty()) {
            for (FactoryLedgerTransaction transaction : transactions.findByReceiptNoInAndSource(numbers, SOURCE)) {
                posted.put(transaction.getReceiptNo(), transaction);
            }
        }
        Map<Long, String> factoryNames = new java.util.HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BillingDocument document : found.getContent()) {
            BillingContent content = content(document);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", document.getId());
            row.put("status", document.getStatus());
            row.put("number", document.getDocumentNumber());
            row.put("customerName", content.getCustomerName());
            row.put("date", content.getDate());
            row.put("itemCount", content.getLines() == null ? 0 : content.getLines().size());
            row.put("total", document.getTotalAmount());
            row.put("updatedAt", document.getUpdateTime());
            FactoryLedgerTransaction transaction = document.getDocumentNumber() == null ? null : posted.get(document.getDocumentNumber());
            row.put("ledgerName", transaction == null || "REVERSED".equals(transaction.getStatus()) ? null
                    : factoryNames.computeIfAbsent(transaction.getLedgerId(), this::factoryName));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("page", found.getNumber());
        result.put("totalPages", found.getTotalPages());
        result.put("total", found.getTotalElements());
        return result;
    }

    public Map<String, Object> post(Long owner, Long documentId, Long requestedLedgerId) {
        if (owner == null || owner <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        BillingDocument document = documents.lockOwned(documentId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "单据不存在"));
        if (!"CONFIRMED".equals(document.getStatus()) || !StringUtils.hasText(document.getDocumentNumber())) {
            throw new IllegalArgumentException("请先保存单据，再记入厂家账本");
        }
        BillingContent content = content(document);
        Long ledgerId = requestedLedgerId != null ? requestedLedgerId : content.getLedgerId();

        Optional<FactoryLedgerTransaction> existing = posted(document);
        if (existing.isPresent()) {
            if (ledgerId != null && !ledgerId.equals(existing.get().getLedgerId())) {
                throw new IllegalArgumentException("这张单据已记入「" + factoryName(existing.get().getLedgerId())
                        + "」的账本，不能再记到别的账本");
            }
            return view(existing);
        }
        if (ledgerId == null) {
            throw new IllegalArgumentException("请选择要记入的厂家账本");
        }

        ledgers.addBillingTransaction(ledgerId, request(document, content), owner);
        return view(posted(document));
    }

    /**
     * 作废已保存的单据：单据标为作废；如果已经记入厂家账本，同时做红字冲销。
     * 单据和流水都不删除，以后可以查到是谁、什么时候、为什么作废的。
     */
    public Map<String, Object> voidDocument(Long owner, Long documentId, String reason) {
        if (owner == null || owner <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        BillingDocument document = documents.lockOwned(documentId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "单据不存在"));
        if ("VOIDED".equals(document.getStatus())) {
            return view(posted(document));
        }
        if (!"CONFIRMED".equals(document.getStatus())) {
            throw new IllegalArgumentException("草稿不需要作废，直接修改即可");
        }
        String why = StringUtils.hasText(reason) ? reason.trim() : "开错了";
        Optional<FactoryLedgerTransaction> posted = posted(document);
        if (posted.isPresent() && !"REVERSED".equals(posted.get().getStatus())) {
            ledgers.reverseTransaction(posted.get().getLedgerId(), posted.get().getId(),
                    "作废单据 " + document.getDocumentNumber() + "：" + why, owner);
        }
        document.setStatus("VOIDED");
        document.setUpdateTime(java.time.LocalDateTime.now());
        documents.saveAndFlush(document);
        return view(posted(document));
    }

    private FactoryLedgerTransactionRequest request(BillingDocument document, BillingContent content) {
        FactoryLedgerTransactionRequest request = new FactoryLedgerTransactionRequest();
        request.setType("PURCHASE");
        request.setDate(date(content.getDate()));
        request.setReceiptNo(document.getDocumentNumber());
        String remark = "销售单 " + document.getDocumentNumber();
        if (StringUtils.hasText(content.getRemark())) {
            remark += " · " + content.getRemark().trim();
        }
        request.setRemark(remark.length() > 255 ? remark.substring(0, 255) : remark);
        List<FactoryLedgerTransactionRequest.Item> items = new ArrayList<>();
        for (BillingContent.Line line : content.getLines()) {
            if (line == null || !StringUtils.hasText(line.getName()) || line.getQuantity() == null || line.getPrice() == null) {
                continue;
            }
            FactoryLedgerTransactionRequest.Item item = new FactoryLedgerTransactionRequest.Item();
            item.setProductName(line.getName().trim());
            item.setSpecification(line.getSpecification());
            item.setUnit(line.getUnit());
            item.setQuantity(line.getQuantity());
            item.setUnitPrice(line.getPrice().setScale(2, RoundingMode.HALF_UP));
            items.add(item);
        }
        request.setItems(items);
        return request;
    }

    private Optional<FactoryLedgerTransaction> posted(BillingDocument document) {
        if (!StringUtils.hasText(document.getDocumentNumber())) {
            return Optional.empty();
        }
        return transactions.findFirstByReceiptNoAndSource(document.getDocumentNumber(), SOURCE);
    }

    private Map<String, Object> view(Optional<FactoryLedgerTransaction> posted) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("posted", posted.isPresent());
        posted.ifPresent(transaction -> {
            result.put("ledgerId", transaction.getLedgerId());
            result.put("factoryName", factoryName(transaction.getLedgerId()));
            result.put("transactionNo", transaction.getTransactionNo());
            result.put("reversed", "REVERSED".equals(transaction.getStatus()));
            result.put("date", transaction.getTransactionDate());
            result.put("amount", BigDecimal.valueOf(transaction.getAmountCents() == null ? 0 : transaction.getAmountCents())
                    .movePointLeft(2).setScale(2, RoundingMode.HALF_UP));
        });
        return result;
    }

    private String factoryName(Long ledgerId) {
        return ledgerRepository.findById(ledgerId).map(FactoryLedger::getFactoryName).orElse("已删除的账本");
    }

    private BillingContent content(BillingDocument document) {
        try {
            return mapper.readValue(document.getContentJson(), BillingContent.class);
        } catch (Exception exception) {
            throw new IllegalStateException("单据内容无法读取", exception);
        }
    }

    private static LocalDate date(String value) {
        if (!StringUtils.hasText(value)) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            return LocalDate.now();
        }
    }
}
