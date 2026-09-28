package com.example.demo.service.impl;

import com.example.demo.dto.InventoryAdjustRequest;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import com.example.demo.entity.OperationLog;
import com.example.demo.entity.Product;
import com.example.demo.repository.InventoryLogRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.InventoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 库存业务逻辑实现类
 */
@Service
@Transactional
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryLogRepository inventoryLogRepository;
    private final ProductRepository productRepository;
    private final OperationLogRepository operationLogRepository;

    public InventoryServiceImpl(
            InventoryRepository inventoryRepository,
            InventoryLogRepository inventoryLogRepository,
            ProductRepository productRepository,
            OperationLogRepository operationLogRepository) {
        this.inventoryRepository = inventoryRepository;
        this.inventoryLogRepository = inventoryLogRepository;
        this.productRepository = productRepository;
        this.operationLogRepository = operationLogRepository;
    }

    @Override
    public Inventory saveInventory(Inventory inventory) {
        if (inventory.getLastUpdateTime() == null) {
            inventory.setLastUpdateTime(LocalDateTime.now());
        }
        if (inventory.getWarningThreshold() == null) {
            inventory.setWarningThreshold(10);
        }
        Inventory saved = inventoryRepository.save(inventory);
        syncProduct(saved);
        saveOperationLog("INVENTORY", "CREATE", "新增库存记录，商品ID：" + saved.getProductId(), 1L);
        return saved;
    }

    @Override
    public Inventory updateInventory(Inventory inventory) {
        Inventory existingInventory = getInventoryById(inventory.getId())
                .orElseThrow(() -> new IllegalArgumentException("库存记录不存在，无法更新"));
        existingInventory.setProductId(inventory.getProductId());
        existingInventory.setQuantity(inventory.getQuantity());
        existingInventory.setLocationId(inventory.getLocationId());
        existingInventory.setWarningThreshold(inventory.getWarningThreshold());
        existingInventory.setLastUpdateTime(LocalDateTime.now());
        Inventory saved = inventoryRepository.save(existingInventory);
        syncProduct(saved);
        saveOperationLog("INVENTORY", "UPDATE", "更新库存记录，商品ID：" + saved.getProductId(), 1L);
        return saved;
    }

    @Override
    public void deleteInventory(Long id) {
        Inventory inventory = getInventoryById(id)
                .orElseThrow(() -> new IllegalArgumentException("库存记录不存在，无法删除"));
        inventoryRepository.deleteById(id);
        saveOperationLog("INVENTORY", "DELETE", "删除库存记录，商品ID：" + inventory.getProductId(), 1L);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Inventory> getInventoryById(Long id) {
        return inventoryRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Inventory> getAllInventories(String keyword, String locationId, Boolean warningOnly) {
        ensureInventoryForProducts();
        return inventoryRepository.findAll().stream()
                .filter(inventory -> matchesKeyword(inventory, keyword))
                .filter(inventory -> !StringUtils.hasText(locationId) || locationId.equalsIgnoreCase(inventory.getLocationId()))
                .filter(inventory -> warningOnly == null || !warningOnly || isWarning(inventory))
                .sorted(Comparator.comparing(Inventory::getLastUpdateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    @Override
    public Inventory adjustInventory(Long id, InventoryAdjustRequest request) {
        Inventory inventory = getInventoryById(id)
                .orElseThrow(() -> new IllegalArgumentException("库存记录不存在，无法盘点调整"));
        int beforeQuantity = defaultInteger(inventory.getQuantity());
        int afterQuantity = defaultInteger(request.getActualQuantity());
        inventory.setQuantity(afterQuantity);
        inventory.setLastUpdateTime(LocalDateTime.now());
        Inventory saved = inventoryRepository.save(inventory);
        syncProduct(saved);

        Product product = productRepository.findById(saved.getProductId()).orElse(null);
        InventoryLog log = new InventoryLog();
        log.setProductId(saved.getProductId());
        log.setProductName(product != null ? product.getName() : null);
        log.setChangeType("盘点");
        log.setQuantity(afterQuantity - beforeQuantity);
        log.setBeforeQuantity(beforeQuantity);
        log.setAfterQuantity(afterQuantity);
        log.setOperatorId(request.getOperatorId());
        log.setRelatedOrderId(saved.getId());
        log.setRemark(request.getReason());
        log.setCreateTime(LocalDateTime.now());
        inventoryLogRepository.save(log);

        saveOperationLog("INVENTORY", "ADJUST", "盘点调整库存，商品ID：" + saved.getProductId(), request.getOperatorId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryLog> getInventoryLogs(Long id) {
        Inventory inventory = getInventoryById(id)
                .orElseThrow(() -> new IllegalArgumentException("库存记录不存在，无法查询日志"));
        return inventoryLogRepository.findByProductIdOrderByCreateTimeDesc(inventory.getProductId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Inventory> getWarnings() {
        return getAllInventories(null, null, true);
    }

    private void ensureInventoryForProducts() {
        for (Product product : productRepository.findAll()) {
            Optional<Inventory> optionalInventory = inventoryRepository.findByProductId(product.getId());
            if (optionalInventory.isEmpty()) {
                Inventory inventory = new Inventory();
                inventory.setProductId(product.getId());
                inventory.setQuantity(defaultInteger(product.getStock()));
                inventory.setLocationId(product.getLocationId());
                inventory.setWarningThreshold(10);
                inventory.setLastUpdateTime(LocalDateTime.now());
                inventoryRepository.save(inventory);
            }
        }
    }

    private boolean matchesKeyword(Inventory inventory, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        Product product = productRepository.findById(inventory.getProductId()).orElse(null);
        if (product == null) {
            return false;
        }
        String normalized = keyword.trim().toLowerCase();
        return containsText(product.getName(), normalized)
                || containsText(product.getBarcode(), normalized);
    }

    private boolean containsText(String value, String keyword) {
        return StringUtils.hasText(value) && value.toLowerCase().contains(keyword);
    }

    private boolean isWarning(Inventory inventory) {
        return defaultInteger(inventory.getQuantity()) <= defaultInteger(inventory.getWarningThreshold());
    }

    private void syncProduct(Inventory inventory) {
        productRepository.findById(inventory.getProductId()).ifPresent(product -> {
            product.setStock(inventory.getQuantity());
            product.setLocationId(inventory.getLocationId());
            productRepository.save(product);
        });
    }

    private void saveOperationLog(String module, String action, String detail, Long operatorId) {
        OperationLog log = new OperationLog();
        log.setOperatorId(operatorId);
        log.setModule(module);
        log.setAction(action);
        log.setDetail(detail);
        log.setCreateTime(LocalDateTime.now());
        operationLogRepository.save(log);
    }

    private int defaultInteger(Integer value) {
        return value == null ? 0 : value;
    }
}
