package com.example.demo.price.service;

import com.example.demo.entity.Product;
import com.example.demo.price.dto.PriceHistoryItem;
import com.example.demo.price.dto.PriceHistoryPage;
import com.example.demo.price.entity.ProductPriceHistory;
import com.example.demo.price.repository.ProductPriceHistoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Transactional
public class ProductPriceHistoryService {
    private final ProductPriceHistoryRepository repository;

    public ProductPriceHistoryService(ProductPriceHistoryRepository repository) {
        this.repository = repository;
    }

    public void recordInitialPrices(Product product, Long operatorId, String sourceType, String requestKey) {
        prices(product).forEach((type, value) -> {
            if (value != null) record(product.getId(), null, type, "INITIAL_PRICE_SET", null, value,
                    true, sourceType, null, product.getId(), null, operatorId, LocalDate.now(),
                    "新建商品初始价格", requestKey + ":" + type);
        });
    }

    public void recordMasterChanges(Product before, Product after, Long operatorId,
                                    String sourceType, Long sourceTaskId,
                                    Long sourceRecordId, String sourceLineKey, String requestKey) {
        recordMasterChanges(before, after, null, null, operatorId, sourceType, sourceTaskId,
                sourceRecordId, sourceLineKey, requestKey);
    }

    public void recordMasterChanges(Product before, Product after, Long supplierId,
                                    LocalDate effectiveDate, Long operatorId,
                                    String sourceType, Long sourceTaskId,
                                    Long sourceRecordId, String sourceLineKey, String requestKey) {
        Map<String, BigDecimal> oldPrices = prices(before);
        prices(after).forEach((type, value) -> {
            BigDecimal old = oldPrices.get(type);
            if (!same(old, value)) record(after.getId(), supplierId, type, "MASTER_PRICE_CHANGED", old, value,
                    true, sourceType, sourceTaskId, sourceRecordId, sourceLineKey, operatorId,
                    effectiveDate == null ? LocalDate.now() : effectiveDate,
                    "修改商品价格", requestKey + ":" + type);
        });
    }

    public void recordPurchasePrice(Long productId, Long supplierId, BigDecimal currentCost,
                                    BigDecimal purchasePrice, Long orderId, String lineKey,
                                    Long operatorId, LocalDate effectiveDate) {
        record(productId, supplierId, "PURCHASE", "PURCHASE_PRICE_OBSERVED", currentCost, purchasePrice,
                false, "PURCHASE_STOCK_IN", null, orderId, lineKey, operatorId, effectiveDate,
                "采购入库价格", "purchase-stock-in:" + orderId + ":" + lineKey + ":PURCHASE");
    }

    private void record(Long productId, Long supplierId, String priceType, String eventType,
                        BigDecimal before, BigDecimal after, boolean applied, String sourceType,
                        Long sourceTaskId, Long sourceRecordId, String sourceLineKey, Long operatorId,
                        LocalDate effectiveDate, String reason, String idempotencyKey) {
        var prior = repository.findByIdempotencyKey(idempotencyKey);
        if (prior.isPresent()) {
            ProductPriceHistory value = prior.get();
            if (value.getProductId().equals(productId) && value.getPriceType().equals(priceType)
                    && same(value.getAfterPrice(), after)) return;
            throw new IllegalArgumentException("价格历史幂等键已用于不同的价格变更");
        }
        ProductPriceHistory value = new ProductPriceHistory();
        value.setProductId(productId);
        value.setSupplierId(supplierId);
        value.setPriceType(priceType);
        value.setEventType(eventType);
        value.setBeforePrice(before);
        value.setAfterPrice(after);
        value.setChangePercent(percent(before, after));
        value.setAppliedToProduct(applied);
        value.setSourceType(sourceType);
        value.setSourceTaskId(sourceTaskId);
        value.setSourceRecordId(sourceRecordId);
        value.setSourceLineKey(sourceLineKey);
        value.setOperatorId(operatorId);
        value.setEffectiveDate(effectiveDate);
        value.setReason(reason);
        value.setIdempotencyKey(idempotencyKey);
        value.setCreateTime(LocalDateTime.now());
        try {
            repository.save(value);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("价格历史记录发生并发冲突", exception);
        }
    }

    @Transactional(readOnly = true)
    public PriceHistoryPage byProduct(Long productId, int page, int size) {
        return toPage(repository.findByProductIdOrderByCreateTimeDescIdDesc(
                productId, PageRequest.of(Math.max(page, 0), safeSize(size))));
    }

    @Transactional(readOnly = true)
    public PriceHistoryPage bySupplier(Long supplierId, int page, int size) {
        return bySupplier(supplierId, null, page, size);
    }

    public PriceHistoryPage bySupplier(Long supplierId, Long productId, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize(size));
        return toPage(productId == null
                ? repository.findBySupplierIdOrderByCreateTimeDescIdDesc(supplierId, pageable)
                : repository.findBySupplierIdAndProductIdOrderByCreateTimeDescIdDesc(
                        supplierId, productId, pageable));
    }

    @Transactional(readOnly = true)
    public PriceHistoryItem require(Long id) {
        return PriceHistoryItem.from(repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("价格历史不存在")));
    }

    private PriceHistoryPage toPage(Page<ProductPriceHistory> page) {
        return new PriceHistoryPage(page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.getContent().stream().map(PriceHistoryItem::from).toList());
    }

    private Map<String, BigDecimal> prices(Product product) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("COST", product.getCostPrice());
        values.put("RETAIL", product.getRetailPrice());
        values.put("WHOLESALE", product.getWholesalePrice());
        values.put("OLD_CUSTOMER", product.getOldCustomerPrice());
        return values;
    }

    private BigDecimal percent(BigDecimal before, BigDecimal after) {
        if (before == null || after == null || before.compareTo(BigDecimal.ZERO) == 0) return null;
        return after.subtract(before).multiply(BigDecimal.valueOf(100))
                .divide(before, 4, RoundingMode.HALF_UP);
    }

    private boolean same(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private int safeSize(int size) {
        return Math.min(Math.max(size, 1), 200);
    }
}
