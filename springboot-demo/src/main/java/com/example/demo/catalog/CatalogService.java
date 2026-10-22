package com.example.demo.catalog;

import com.example.demo.catalog.dto.CatalogItemView;
import com.example.demo.catalog.dto.CatalogPageView;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.Product;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.PurchaseOrderItem;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.SalesOrderItem;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.PurchaseOrderRepository;
import com.example.demo.repository.SalesOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CatalogService {
    private com.example.demo.billing.BillingProductUsageRepository billingUsages;
    @org.springframework.beans.factory.annotation.Autowired public void setBillingUsages(com.example.demo.billing.BillingProductUsageRepository usages) { this.billingUsages=usages; }
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;

    public CatalogService(ProductRepository productRepository,
                          InventoryRepository inventoryRepository,
                          SalesOrderRepository salesOrderRepository,
                          PurchaseOrderRepository purchaseOrderRepository) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
    }

    public CatalogPageView list(String view, String keyword, int page, int size) {
        return list(view,keyword,"",page,size);
    }
    public CatalogPageView list(String view, String keyword, String factory, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        String wantedView = StringUtils.hasText(view) ? view.trim().toLowerCase(Locale.ROOT) : "frequent";
        if (!List.of("frequent", "attention", "all").contains(wantedView)) {
            throw new IllegalArgumentException("商品视图只能是 frequent、attention 或 all");
        }

        List<Product> products = productRepository.findAll();
        Map<Long, Inventory> inventories = inventoryRepository.findAll().stream()
                .collect(Collectors.toMap(Inventory::getProductId, Function.identity(), (left, right) -> left));
        Map<Long, Activity> activities = activities();
        List<CatalogItemView> all = products.stream()
                .filter(product -> factory==null||factory.isBlank()||factory.equalsIgnoreCase(safe(product.getSourceFactory()).trim()))
                .filter(product -> matches(product, keyword))
                .map(product -> view(product, inventories.get(product.getId()), activities.get(product.getId())))
                .toList();

        Comparator<CatalogItemView> nameOrder = Comparator.comparing(
                (CatalogItemView item) -> safe(item.name()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CatalogItemView::id);
        Comparator<CatalogItemView> frequentOrder = Comparator
                .comparingInt(CatalogItemView::activityCount).reversed()
                .thenComparing(CatalogItemView::lastActivityAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(nameOrder);
        List<CatalogItemView> filtered = all.stream()
                .filter(item -> !"frequent".equals(wantedView) || item.activityCount() > 0)
                .filter(item -> !"attention".equals(wantedView) || !item.attentionReasons().isEmpty())
                .sorted("frequent".equals(wantedView) ? frequentOrder :
                        "attention".equals(wantedView) ? Comparator.comparingInt((CatalogItemView item) -> item.attentionReasons().size()).reversed().thenComparing(nameOrder) : nameOrder)
                .toList();

        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        int totalPages = filtered.isEmpty() ? 0 : (filtered.size() + safeSize - 1) / safeSize;
        int recorded = (int) products.stream().filter(product -> inventories.containsKey(product.getId())).count();
        int attention = (int) products.stream().map(product -> view(product, inventories.get(product.getId()), activities.get(product.getId())))
                .filter(item -> !item.attentionReasons().isEmpty()).count();
        return new CatalogPageView(filtered.subList(from, to), safePage, safeSize, filtered.size(), totalPages,
                products.size(), recorded, attention);
    }

    private Map<Long, Activity> activities() {
        Map<Long, Activity> result = new HashMap<>();
        if(billingUsages!=null)for(var usage:billingUsages.findAll()) {
            if(usage.getCandidateKey()==null||!usage.getCandidateKey().startsWith("product:"))continue;
            try {
                var activity=result.computeIfAbsent(Long.valueOf(usage.getCandidateKey().substring(8)),ignored->new Activity());
                activity.count+=Math.toIntExact(Math.min(usage.getUseCount()==null?0:usage.getUseCount(),Integer.MAX_VALUE));
                activity.lastUsedAt=latest(activity.lastUsedAt,usage.getLastUsedTime());
            }catch(NumberFormatException ignored){}
        }
        for (SalesOrder order : salesOrderRepository.findAll()) {
            LocalDateTime time = latest(order.getOrderTime(), order.getCreateTime());
            for (SalesOrderItem item : safeSalesItems(order.getItems())) {
                if (item == null || item.getProductId() == null) continue;
                result.computeIfAbsent(item.getProductId(), ignored -> new Activity()).sale(time);
            }
        }
        for (PurchaseOrder order : purchaseOrderRepository.findAll()) {
            LocalDateTime time = latest(order.getOrderTime(), order.getCreateTime());
            for (PurchaseOrderItem item : safePurchaseItems(order.getItems())) {
                if (item == null || item.getProductId() == null) continue;
                result.computeIfAbsent(item.getProductId(), ignored -> new Activity()).purchase(time);
            }
        }
        return result;
    }

    private CatalogItemView view(Product product, Inventory inventory, Activity activity) {
        boolean recorded = inventory != null;
        int quantity = recorded && inventory.getQuantity() != null ? inventory.getQuantity() : defaultInteger(product.getStock());
        int threshold = recorded && inventory.getWarningThreshold() != null ? inventory.getWarningThreshold() : 10;
        List<String> reasons = new ArrayList<>();
        if (!recorded) reasons.add("库存未记录");
        else if (quantity <= threshold) reasons.add(quantity <= 0 ? "缺货" : "库存不多");
        if (product.getRetailPrice() == null || product.getRetailPrice().compareTo(BigDecimal.ZERO) <= 0) reasons.add("售价未填写");
        String state = !recorded ? "UNKNOWN" : quantity <= 0 ? "OUT_OF_STOCK" : quantity <= threshold ? "LOW" : "IN_STOCK";
        Activity current = activity == null ? new Activity() : activity;
        return new CatalogItemView(product.getId(), product.getName(), product.getBarcode(), product.getSpec(),
                product.getUnit(), product.getRetailPrice(), product.getCostPrice(), product.getWholesalePrice(), product.getOldCustomerPrice(),
                product.getSourceFactory(), product.getImageUrl(), recorded ? inventory.getId() : null,
                quantity, threshold, recorded && StringUtils.hasText(inventory.getLocationId()) ? inventory.getLocationId() : product.getLocationId(),
                state, recorded, recorded && quantity <= threshold, current.lastSoldAt, current.lastPurchasedAt,
                latest(latest(current.lastSoldAt, current.lastPurchasedAt), current.lastUsedAt), current.count, reasons);
    }

    private boolean matches(Product product, String keyword) {
        if (!StringUtils.hasText(keyword)) return true;
        String value = keyword.trim().toLowerCase(Locale.ROOT);
        return contains(product.getName(), value) || contains(product.getBarcode(), value)
                || contains(product.getSpec(), value) || contains(product.getSourceFactory(), value);
    }

    private boolean contains(String source, String keyword) {
        return StringUtils.hasText(source) && source.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private List<SalesOrderItem> safeSalesItems(List<SalesOrderItem> items) { return items == null ? List.of() : items; }
    private List<PurchaseOrderItem> safePurchaseItems(List<PurchaseOrderItem> items) { return items == null ? List.of() : items; }
    private int defaultInteger(Integer value) { return value == null ? 0 : value; }
    private String safe(String value) { return value == null ? "" : value; }
    private LocalDateTime latest(LocalDateTime left, LocalDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private static class Activity {
        private LocalDateTime lastUsedAt;
        private LocalDateTime lastSoldAt;
        private LocalDateTime lastPurchasedAt;
        private int count;
        private void sale(LocalDateTime time) { lastSoldAt = later(lastSoldAt, time); count++; }
        private void purchase(LocalDateTime time) { lastPurchasedAt = later(lastPurchasedAt, time); count++; }
        private LocalDateTime later(LocalDateTime current, LocalDateTime candidate) {
            if (current == null) return candidate;
            if (candidate == null) return current;
            return candidate.isAfter(current) ? candidate : current;
        }
    }
}
