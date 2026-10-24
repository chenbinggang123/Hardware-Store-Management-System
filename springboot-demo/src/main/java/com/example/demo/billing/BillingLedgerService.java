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
    private BillingProductSyncService productSync;
    @org.springframework.beans.factory.annotation.Autowired public void setProductSync(BillingProductSyncService sync) { this.productSync=sync; }
    private com.example.demo.agent.service.BillingPhotoService photos;
    @org.springframework.beans.factory.annotation.Autowired(required = false) public void setPhotos(com.example.demo.agent.service.BillingPhotoService photos) { this.photos=photos; }
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
        return records(owner, keyword, "", page, size);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> records(Long owner, String keyword, String status, int page, int size) {
        return records(owner,keyword,status,"","",page,size);
    }
    @Transactional(readOnly = true)
    public Map<String, Object> records(Long owner, String keyword, String status, String documentType, String factory, int page, int size) {
        if (owner == null || owner <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        String text = keyword == null ? "" : keyword.trim();
        if (text.length() > 40) text = text.substring(0, 40);
        int pageSize = Math.min(Math.max(size, 1), 50);
        String selectedStatus = status == null ? "" : status.trim();
        if (!selectedStatus.isEmpty() && !java.util.Set.of("DRAFT", "CONFIRMED", "VOIDED").contains(selectedStatus)) {
            throw new IllegalArgumentException("单据状态无效");
        }
        var pageable = org.springframework.data.domain.PageRequest.of(Math.max(page, 0), pageSize);
        if(documentType==null)documentType="";
        if(factory==null)factory="";
        if(!documentType.isEmpty()&&!java.util.Set.of("PURCHASE","RETURN","PROCUREMENT").contains(documentType))throw new IllegalArgumentException("单据类型无效");
        org.springframework.data.domain.Page<BillingDocument> found = !documentType.isEmpty() || !factory.isEmpty()
                ? documents.searchFiltered(owner,text,selectedStatus,documentType,factory,pageable) : selectedStatus.isEmpty()
                ? documents.search(owner, text, pageable) : documents.searchByStatus(owner, text, selectedStatus, pageable);
        Map<Long, String> factoryNames = new java.util.HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BillingDocument document : found.getContent()) {
            BillingContent content = content(document);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", document.getId());
            row.put("status", document.getStatus());
            row.put("documentType", content.getDocumentType());
            row.put("number", document.getDocumentNumber());
            row.put("customerName", content.getCustomerName());
            row.put("date", content.getDate());
            row.put("itemCount", content.getLines() == null ? 0 : content.getLines().size());
            row.put("total", document.getTotalAmount());
            row.put("updatedAt", document.getUpdateTime());
            FactoryLedgerTransaction transaction = posted(document).orElse(null);
            row.put("ledgerManaged", transaction != null);
            row.put("ledgerId", transaction == null ? null : transaction.getLedgerId());
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
        boolean procurement = "PROCUREMENT".equals(content.getDocumentType());
        Long ledgerId = requestedLedgerId != null ? requestedLedgerId : content.getLedgerId();

        Optional<FactoryLedgerTransaction> existing = posted(document);
        if (existing.isPresent() && !"REVERSED".equals(existing.get().getStatus())) {
            if (ledgerId != null && !ledgerId.equals(existing.get().getLedgerId())) {
                throw new IllegalArgumentException("这张单据已记入「" + factoryName(existing.get().getLedgerId())
                        + "」的账本，不能再记到别的账本");
            }
            return view(existing);
        }
        if (ledgerId == null && procurement) {
            // 采购单不用选账本：按源头厂商记入“进货的”账本，没有就自动建一本。
            ledgerId = ledgers.findOrCreateSupplierLedger(content.getCustomerName(), owner).getId();
        }
        if (ledgerId == null) {
            throw new IllegalArgumentException("请选择要记入的厂家账本");
        }
        boolean supplierLedger = FactoryLedgerService.KIND_SUPPLIER.equals(ledgers.kindOf(ledgerId));
        if (procurement && !supplierLedger) {
            throw new IllegalArgumentException("采购单要记入“进货的”账本");
        }
        if (!procurement && supplierLedger) {
            throw new IllegalArgumentException("拿货单和退货单要记入“买的”账本");
        }

        ledgers.addBillingTransaction(ledgerId, request(document, content), owner);
        return view(posted(document));
    }

    /** 旧版本保存的采购单没有记入进货账本：已保存、有厂商和明细、还没记过账的采购单。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> unpostedProcurements(Long owner) {
        if (owner == null || owner <= 0) throw new IllegalArgumentException("请先登录");
        List<Map<String, Object>> result = new ArrayList<>();
        for (BillingDocument document : documents.findByOperatorIdAndStatusIn(owner, List.of("CONFIRMED"))) {
            BillingContent content;
            try { content = content(document); } catch (RuntimeException exception) { continue; }
            if (!"PROCUREMENT".equals(content.getDocumentType()) || !StringUtils.hasText(document.getDocumentNumber())) continue;
            if (!StringUtils.hasText(content.getCustomerName()) || request(document, content).getItems().isEmpty()) continue;
            if (!postings(document).isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", document.getId());
            row.put("number", document.getDocumentNumber());
            row.put("factoryName", content.getCustomerName());
            row.put("date", content.getDate());
            row.put("total", document.getTotalAmount());
            result.add(row);
        }
        return result;
    }

    /** 把上面这些采购单一次记入各自厂商的进货账本；已记过账的会被跳过，所以重复点击不会重复记账。 */
    public Map<String, Object> backfillProcurements(Long owner) {
        List<Map<String, Object>> pending = unpostedProcurements(owner);
        for (Map<String, Object> row : pending) {
            post(owner, ((Number) row.get("id")).longValue(), null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("posted", pending.size());
        return result;
    }

    /**
     * 修改已保存的单据：退回草稿。如果已经记入厂家账本，先红字冲销那一笔；
     * 改完再“保存并记账”时，会按修订号（单号-R2、-R3…）重新记一笔，账本里新旧两笔都能查到。
     */
    public Map<String, Object> reopen(Long owner, Long documentId) {
        if (owner == null || owner <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        BillingDocument document = documents.lockOwned(documentId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "单据不存在"));
        requireUnposted(document);
        if ("VOIDED".equals(document.getStatus())) {
            throw new IllegalArgumentException("已作废的单据不能修改，可以“复制为新单”");
        }
        if ("CONFIRMED".equals(document.getStatus())) {
            if (productSync != null) productSync.reverse(owner, documentId);
            Optional<FactoryLedgerTransaction> posted = posted(document);
            if (posted.isPresent() && !"REVERSED".equals(posted.get().getStatus())) {
                ledgers.reverseTransaction(posted.get().getLedgerId(), posted.get().getId(),
                        "修改单据 " + document.getDocumentNumber() + "，改完后重新记账", owner);
            }
            document.setStatus("DRAFT");
            document.setUpdateTime(java.time.LocalDateTime.now());
            documents.saveAndFlush(document);
        }
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
        requireUnposted(document);
        if ("VOIDED".equals(document.getStatus())) {
            return view(posted(document));
        }
        if (!"CONFIRMED".equals(document.getStatus())) {
            throw new IllegalArgumentException("草稿不需要作废，直接修改即可");
        }
        String why = StringUtils.hasText(reason) ? reason.trim() : "开错了";
        if (productSync != null) productSync.reverse(owner, documentId);
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

    /** 先锁定并校验全部选择，再一起删除，避免混入已保存单据或出现部分删除。 */
    public int deleteDrafts(Long owner, List<Long> ids) {
        return deleteDraftsAndPhotos(owner, ids).deleted();
    }

    /** 删除结果：删掉的张数，以及没有入账、需要到云存储清理的原图文件。 */
    public record Deleted(int deleted, List<String> cloudFiles) { }

    public Deleted deleteDraftsAndPhotos(Long owner, List<Long> ids) {
        if (owner == null || owner <= 0) throw new IllegalArgumentException("请先登录");
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("请选择 1 到 100 张草稿");
        }
        List<BillingDocument> selected = new ArrayList<>();
        for (Long id : ids.stream().distinct().sorted().toList()) {
            BillingDocument document = documents.lockOwned(id, owner)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "部分草稿已不存在，请刷新后重新选择"));
            if (!"DRAFT".equals(document.getStatus())) {
                throw new IllegalArgumentException("部分单据已保存或作废，本次没有删除，请刷新后重新选择草稿");
            }
            requireUnposted(document);
            selected.add(document);
        }
        List<Long> photoIds = selected.stream().map(this::sourcePhotoId).filter(java.util.Objects::nonNull).toList();
        documents.deleteAll(selected);
        documents.flush();
        return new Deleted(selected.size(), photos == null ? List.of() : photos.discard(photoIds, owner));
    }

    private Long sourcePhotoId(BillingDocument document) {
        try { return content(document).getSourceAttachmentId(); } catch (RuntimeException exception) { return null; }
    }

    /** 开单记录只管理未记入账本的单据。 */
    public void deleteDocument(Long owner, Long documentId) {
        deleteDocumentAndPhoto(owner, documentId);
    }

    public List<String> deleteDocumentAndPhoto(Long owner, Long documentId) {
        if (owner == null || owner <= 0) throw new IllegalArgumentException("请先登录");
        BillingDocument document = documents.lockOwned(documentId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "单据不存在"));
        if (productSync != null) productSync.reverse(owner, documentId);
        requireUnposted(document);
        Long photoId = sourcePhotoId(document);
        documents.delete(document);
        documents.flush();
        return photos == null || photoId == null ? List.of() : photos.discard(List.of(photoId), owner);
    }

    private void requireUnposted(BillingDocument document) {
        if (!postings(document).isEmpty()) throw new IllegalArgumentException("这张单据已进入工厂账本，请到账本中处理，不能在开单记录中删除、作废或退回草稿");
    }

    /** 从账本删除来源单据；服务端核对账本和来源，原流水及抵消记录保留。 */
    public void deleteFromLedger(Long owner, Long ledgerId, Long transactionId) {
        deleteFromLedgerAndPhoto(owner, ledgerId, transactionId);
    }

    /** 从账本删除单据后，原图不再属于任何入账单据，一并清理，返回需要删的云文件。 */
    public List<String> deleteFromLedgerAndPhoto(Long owner, Long ledgerId, Long transactionId) {
        if (owner == null || owner <= 0) throw new IllegalArgumentException("请先登录");
        FactoryLedgerTransaction selected = transactions.findById(transactionId)
                .filter(t -> ledgerId.equals(t.getLedgerId()) && SOURCE.equals(t.getSource()))
                .orElseThrow(() -> new IllegalArgumentException("账本单据不存在"));
        String number = selected.getReceiptNo().replaceFirst("-R\\d+$", "");
        BillingDocument found = documents.findByDocumentNumber(number)
                .orElseThrow(() -> new IllegalArgumentException("原单据已删除，请刷新账本"));
        BillingDocument document = documents.lockOwned(found.getId(), owner)
                .orElseThrow(() -> new IllegalArgumentException("无权删除其他操作人创建的单据"));
        if (postings(document).stream().noneMatch(t -> transactionId.equals(t.getId()))) {
            throw new IllegalArgumentException("流水与单据不匹配");
        }
        for (FactoryLedgerTransaction transaction : postings(document)) {
            if (!"REVERSED".equals(transaction.getStatus())) {
                ledgers.reverseTransaction(transaction.getLedgerId(), transaction.getId(),
                        "删除单据 " + document.getDocumentNumber(), owner);
            }
        }
        // 采购单删除时把加过的库存一并退回。
        if (productSync != null) productSync.reverse(owner, document.getId());
        Long photoId = sourcePhotoId(document);
        documents.delete(document);
        documents.flush();
        return photos == null || photoId == null ? List.of() : photos.discard(List.of(photoId), owner);
    }

    private FactoryLedgerTransactionRequest request(BillingDocument document, BillingContent content) {
        FactoryLedgerTransactionRequest request = new FactoryLedgerTransactionRequest();
        request.setType("RETURN".equals(content.getDocumentType()) ? "RETURN" : "PURCHASE");
        request.setDate(date(content.getDate()));
        // 同一张单据改过后再记账，用修订号区分，避开“同一账本收据号不能重复”的限制。
        int revisions = postings(document).size();
        request.setReceiptNo(revisions == 0 ? document.getDocumentNumber() : document.getDocumentNumber() + "-R" + (revisions + 1));
        String remark = ("RETURN".equals(content.getDocumentType()) ? "退货单 "
                : "PROCUREMENT".equals(content.getDocumentType()) ? "采购单 " : "拿货单 ") + document.getDocumentNumber();
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
        return postings(document).stream().max(java.util.Comparator.comparing(
                FactoryLedgerTransaction::getId, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
    }

    /** 这张单据的全部记账记录：单号本身，以及修改后重新记账的 单号-R2、-R3…。 */
    private List<FactoryLedgerTransaction> postings(BillingDocument document) {
        String number = document.getDocumentNumber();
        if (!StringUtils.hasText(number)) {
            return List.of();
        }
        return transactions.findBySourceAndReceiptNoStartingWith(SOURCE, number).stream()
                .filter(transaction -> number.equals(transaction.getReceiptNo())
                        || (transaction.getReceiptNo() != null && transaction.getReceiptNo().startsWith(number + "-R")))
                .toList();
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
