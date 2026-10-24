package com.example.demo.billing;

import com.example.demo.entity.*;
import com.example.demo.repository.*;
import com.example.demo.service.ProductService;
import com.example.demo.price.service.ProductPriceHistoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 确认单据时同步商品；采购库存与撤回库存和原单据在同一事务中提交。 */
@Service
@Transactional
public class BillingProductSyncService {
    private static final String IN = "采购单入库", OUT = "采购单撤回";
    private final ProductRepository products;
    private final ProductService productService;
    private final InventoryRepository inventories;
    private final InventoryLogRepository logs;
    private final ProductPriceHistoryService prices;

    public BillingProductSyncService(ProductRepository products, ProductService productService, InventoryRepository inventories,
                                    InventoryLogRepository logs, ProductPriceHistoryService prices) {
        this.products=products; this.productService=productService; this.inventories=inventories; this.logs=logs; this.prices=prices;
    }

    private ProductRenameService renamer;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRenamer(ProductRenameService renamer) { this.renamer = renamer; }

    /**
     * 单据里已绑定商品库的明细改了名称（规格、单位没变），就是在纠正商品名：商品库跟着改，
     * 其他引用这个商品的单据和账本明细也一起改。新名字已经是另一个商品时不改，按原规则匹配。
     */
    private void renameLinkedProduct(BillingContent.Line line) {
        if (renamer == null || line.getCandidateKey() == null || !line.getCandidateKey().startsWith("product:")) return;
        Long id;
        try { id = Long.valueOf(line.getCandidateKey().substring(8)); } catch (NumberFormatException exception) { return; }
        Product linked = products.findById(id).orElse(null);
        if (linked == null || line.getName() == null || line.getName().trim().isEmpty()) return;
        String newName = line.getName().trim();
        if (newName.equalsIgnoreCase(value(linked.getName()))) return;
        if (!value(linked.getSpec()).equalsIgnoreCase(value(line.getSpecification()))
                || !value(linked.getUnit()).equalsIgnoreCase(value(line.getUnit()))) return;
        if (!products.lockIdentity(newName, value(line.getSpecification()), line.getUnit()).isEmpty()) return;
        String oldName = linked.getName();
        linked.setName(newName);
        products.save(linked);
        renamer.propagate(id, oldName, newName);
    }

    public void confirm(Long owner, BillingDocument document, BillingContent content) {
        boolean procurement = "PROCUREMENT".equals(content.getDocumentType());
        Map<Long, Integer> quantities = new TreeMap<>();
        int index = 0;
        for (BillingContent.Line line : content.getLines()) {
            renameLinkedProduct(line);
            List<Product> matching = products.lockIdentity(line.getName(), value(line.getSpecification()), line.getUnit());
            String factory = procurement ? content.getCustomerName().trim() : "";
            Product product = null;
            if (line.getCandidateKey() != null && line.getCandidateKey().startsWith("product:")) {
                String selected = line.getCandidateKey().substring(8);
                product = matching.stream().filter(p -> p.getId().toString().equals(selected))
                        .filter(p -> !procurement || value(p.getSourceFactory()).isBlank() || value(p.getSourceFactory()).equalsIgnoreCase(factory)).findFirst().orElse(null);
            }
            if (product == null) {
                List<Product> candidates = matching.stream().filter(p -> !procurement || value(p.getSourceFactory()).equalsIgnoreCase(factory)).toList();
                if (candidates.isEmpty() && procurement) candidates = matching.stream().filter(p -> value(p.getSourceFactory()).isBlank()).toList();
                if (candidates.size() > 1) throw new IllegalArgumentException("商品「"+line.getName()+"」有多条相同资料，请从商品候选中明确选择厂商");
                if (candidates.size() == 1) product = candidates.get(0);
            }
            boolean created = product == null;
            if (created) {
                product = new Product(); product.setName(line.getName()); product.setSpec(value(line.getSpecification())); product.setUnit(line.getUnit());
                product.setSourceFactory(factory); product.setStock(0); product.setStatus(1);
                product.setRetailPrice(BigDecimal.ZERO); product.setCostPrice(BigDecimal.ZERO);
                product.setWholesalePrice(BigDecimal.ZERO); product.setOldCustomerPrice(BigDecimal.ZERO);
            }
            Product before = new Product(); before.setId(product.getId()); before.setCostPrice(product.getCostPrice());
            before.setRetailPrice(product.getRetailPrice()); before.setWholesalePrice(product.getWholesalePrice()); before.setOldCustomerPrice(product.getOldCustomerPrice());
            if (procurement) { product.setCostPrice(line.getPrice()); product.setSourceFactory(factory); }
            else if (!"RETURN".equals(content.getDocumentType())) product.setRetailPrice(line.getPrice());
            product = created ? productService.saveProduct(product, owner) : products.save(product);
            if (!created) prices.recordMasterChanges(before, product, owner, "BILLING_DOCUMENT", null, document.getId(), String.valueOf(index),
                    "billing:"+document.getId()+":"+document.getVersion()+":"+index);
            line.setCandidateKey("product:"+product.getId());
            line.setReferenceSource("商品库");
            if (procurement) quantities.merge(product.getId(), line.getQuantity().intValueExact(), Math::addExact);
            index++;
        }
        for (var entry : quantities.entrySet()) move(owner, document.getId(), entry.getKey(), entry.getValue(), IN);
    }

    public void reverse(Long owner, Long documentId) {
        Map<Long, Integer> remaining = new TreeMap<>();
        for (InventoryLog log : logs.findByRelatedOrderIdAndChangeTypeIn(documentId, List.of(IN, OUT))) {
            remaining.merge(log.getProductId(), log.getQuantity(), Math::addExact);
        }
        for (var entry : remaining.entrySet()) if (entry.getValue() != 0) move(owner, documentId, entry.getKey(), -entry.getValue(), OUT);
    }

    private void move(Long owner, Long documentId, Long productId, int delta, String type) {
        Product product = products.findById(productId).orElseThrow(() -> new IllegalArgumentException("采购商品已不存在"));
        // 与商品资料的写锁一致，再读取该商品最新的库存。
        products.lockIdentity(product.getName(), value(product.getSpec()), product.getUnit());
        Inventory inventory = inventories.lockByProductId(productId).orElseGet(() -> {
            Inventory value = new Inventory(); value.setProductId(productId); value.setQuantity(product.getStock()==null?0:product.getStock());
            value.setWarningThreshold(10); value.setLocationId(product.getLocationId()); return value;
        });
        int before = inventory.getQuantity()==null?0:inventory.getQuantity();
        long after = (long) before + delta;
        if (after < 0 || after > Integer.MAX_VALUE) throw new IllegalArgumentException("商品「"+product.getName()+"」当前数量不足以撤回进货，或数量超出范围，请先核对数量");
        inventory.setQuantity((int)after); inventory.setLastUpdateTime(LocalDateTime.now()); inventories.save(inventory);
        product.setStock((int)after); products.save(product);
        InventoryLog log = new InventoryLog(); log.setProductId(productId); log.setProductName(product.getName());
        log.setChangeType(type); log.setQuantity(delta); log.setBeforeQuantity(before); log.setAfterQuantity((int)after);
        log.setRelatedOrderId(documentId); log.setOperatorId(owner); log.setRemark(type+" #"+documentId); log.setCreateTime(LocalDateTime.now()); logs.save(log);
    }

    private static String value(String value) { return value==null?"":value.trim(); }
}
