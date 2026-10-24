package com.example.demo.agent.service;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.billing.BillingContent;
import com.example.demo.billing.BillingDocument;
import com.example.demo.billing.BillingDocumentRepository;
import com.example.demo.entity.Product;
import com.example.demo.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 单据原图：拍照开单时保存的照片。商品页、工厂账本是全店共用的，所以这里按单据找原图，
 * 只返回云存储文件 ID、原图宽高和商品所在那一行的位置，前端据此显示整张图或截出那一行。
 */
@Service
@Transactional(readOnly = true)
public class BillingPhotoService {
    private static final int PRODUCT_PHOTO_LIMIT = 12;

    private final AgentAttachmentRepository attachments;
    private final BillingDocumentRepository documents;
    private final ProductRepository products;
    private final ObjectMapper mapper;
    private final com.example.demo.repository.FactoryLedgerTransactionRepository transactions;

    public BillingPhotoService(AgentAttachmentRepository attachments, BillingDocumentRepository documents,
                               ProductRepository products, ObjectMapper mapper,
                               com.example.demo.repository.FactoryLedgerTransactionRepository transactions) {
        this.attachments = attachments;
        this.documents = documents;
        this.products = products;
        this.mapper = mapper;
        this.transactions = transactions;
    }

    /** 原图只有在单据记入工厂账本后才算保存：账本里有这张单据一笔未冲销的流水。 */
    private boolean posted(BillingDocument document) {
        String number = document.getDocumentNumber();
        if (!StringUtils.hasText(number)) return false;
        return transactions.findBySourceAndReceiptNoStartingWith("BILLING", number).stream()
                .anyMatch(item -> !"REVERSED".equals(item.getStatus()) && item.getReceiptNo() != null
                        && (number.equals(item.getReceiptNo()) || item.getReceiptNo().startsWith(number + "-R")));
    }

    /**
     * 清理没有入账的原图：只要有一张已记账（含已冲销）的单据引用它就保留，否则删掉记录。
     * 返回需要由小程序去云存储删除的文件 ID。
     */
    @Transactional
    public List<String> discard(Collection<Long> attachmentIds, Long operatorId) {
        List<String> cloudFiles = new ArrayList<>();
        if (attachmentIds == null) return cloudFiles;
        for (Long id : attachmentIds.stream().filter(Objects::nonNull).distinct().toList()) {
            AgentAttachment attachment = attachments.findByIdAndOperatorId(id, operatorId).orElse(null);
            if (attachment == null || attachment.getMimeType() == null || !attachment.getMimeType().startsWith("image/")) continue;
            boolean kept = documents.findReferencing(id).stream().anyMatch(document -> hasPosting(document));
            if (kept) continue;
            if (attachment.getObjectKey() != null && attachment.getObjectKey().startsWith("cloud://")) cloudFiles.add(attachment.getObjectKey());
            attachments.delete(attachment);
        }
        return cloudFiles;
    }

    /** 清理超过 days 天还没有入账的拍单原图（只动开单页上传的 billing-photo），返回需要删的云文件。 */
    @Transactional
    public List<String> discardStale(Long operatorId, int days) {
        var before = java.time.LocalDateTime.now().minusDays(Math.max(days, 1));
        List<Long> ids = attachments.findByOperatorIdAndObjectKeyContainingAndCreateTimeBefore(operatorId, "billing-photo/", before)
                .stream().map(AgentAttachment::getId).toList();
        return discard(ids, operatorId);
    }

    private boolean hasPosting(BillingDocument document) {
        String number = document.getDocumentNumber();
        if (!StringUtils.hasText(number)) return false;
        return transactions.findBySourceAndReceiptNoStartingWith("BILLING", number).stream()
                .anyMatch(item -> item.getReceiptNo() != null && (number.equals(item.getReceiptNo()) || item.getReceiptNo().startsWith(number + "-R")));
    }

    /** 账本里每张单据的原图（没有原图的单据不出现在结果里）。 */
    public Map<Long, Map<String, Object>> forDocuments(Collection<Long> documentIds) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        if (documentIds == null || documentIds.isEmpty()) return result;
        for (BillingDocument document : documents.findAllById(documentIds)) {
            if (!posted(document)) continue;
            BillingContent content = content(document);
            Map<String, Object> photo = photo(content == null ? null : content.getSourceAttachmentId());
            if (photo != null) result.put(document.getId(), photo);
        }
        return result;
    }

    /** 这个商品出现过的已保存单据原图，最近的在前，带上商品在原图上那一行的位置。 */
    public List<Map<String, Object>> forProduct(Long productId) {
        Product product = products.findById(productId).orElseThrow(() -> new IllegalArgumentException("商品不存在"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (BillingDocument document : documents.findConfirmedMentioning("product:" + productId, Objects.toString(product.getName(), ""))) {
            if (result.size() >= PRODUCT_PHOTO_LIMIT) break;
            if (!posted(document)) continue;
            BillingContent content = content(document);
            if (content == null || content.getSourceAttachmentId() == null || content.getLines() == null) continue;
            BillingContent.Line line = content.getLines().stream().filter(row -> matches(row, product)).findFirst().orElse(null);
            if (line == null) continue;
            Map<String, Object> photo = photo(content.getSourceAttachmentId());
            if (photo == null) continue;
            Map<String, Object> row = new LinkedHashMap<>(photo);
            row.put("documentId", document.getId());
            row.put("documentNumber", document.getDocumentNumber());
            row.put("documentType", content.getDocumentType());
            row.put("customerName", content.getCustomerName());
            row.put("date", content.getDate());
            row.put("quantity", line.getQuantity());
            row.put("unit", line.getUnit());
            row.put("price", line.getPrice());
            Object rows = photo.get("rows");
            row.put("box", line.getSourceRow() != null && rows instanceof Map<?, ?> boxes ? boxes.get(String.valueOf(line.getSourceRow())) : null);
            row.remove("rows");
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> photo(Long attachmentId) {
        if (attachmentId == null) return null;
        AgentAttachment attachment = attachments.findById(attachmentId).orElse(null);
        if (attachment == null || attachment.getMimeType() == null || !attachment.getMimeType().startsWith("image/")
                || attachment.getObjectKey() == null || !attachment.getObjectKey().startsWith("cloud://")) return null;
        Map<String, Object> photo = new LinkedHashMap<>();
        photo.put("cloudFileId", attachment.getObjectKey());
        photo.put("width", null);
        photo.put("height", null);
        photo.put("rows", Map.of());
        if (StringUtils.hasText(attachment.getOcrLayout())) {
            try {
                JsonNode layout = mapper.readTree(attachment.getOcrLayout());
                if (layout.path("width").isNumber()) photo.put("width", layout.path("width").asInt());
                if (layout.path("height").isNumber()) photo.put("height", layout.path("height").asInt());
                photo.put("rows", mapper.convertValue(layout.path("rows"), Map.class));
            } catch (Exception ignored) { /* 没有行位置时只显示整张图 */ }
        }
        return photo;
    }

    private boolean matches(BillingContent.Line line, Product product) {
        if (line == null) return false;
        if (("product:" + product.getId()).equals(line.getCandidateKey())) return true;
        return same(line.getName(), product.getName()) && same(line.getSpecification(), product.getSpec());
    }

    private boolean same(String left, String right) {
        String a = left == null ? "" : left.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String b = right == null ? "" : right.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return a.equals(b);
    }

    private BillingContent content(BillingDocument document) {
        try {
            return mapper.readValue(document.getContentJson(), BillingContent.class);
        } catch (Exception exception) {
            return null;
        }
    }
}
