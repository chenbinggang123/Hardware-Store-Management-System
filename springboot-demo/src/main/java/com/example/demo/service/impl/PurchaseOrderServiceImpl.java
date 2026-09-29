package com.example.demo.service.impl;

import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import com.example.demo.entity.OperationLog;
import com.example.demo.entity.Product;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.PurchaseOrderItem;
import com.example.demo.repository.InventoryLogRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.PurchaseOrderRepository;
import com.example.demo.service.PurchaseOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 采购订单业务逻辑实现类
 */
@Service
@Transactional
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryLogRepository inventoryLogRepository;
    private final OperationLogRepository operationLogRepository;

    public PurchaseOrderServiceImpl(
            PurchaseOrderRepository purchaseOrderRepository,
            ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            InventoryLogRepository inventoryLogRepository,
            OperationLogRepository operationLogRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryLogRepository = inventoryLogRepository;
        this.operationLogRepository = operationLogRepository;
    }

    @Override
    public PurchaseOrder savePurchaseOrder(PurchaseOrder purchaseOrder) {
        normalizePurchaseOrder(purchaseOrder, false);
        PurchaseOrder saved = purchaseOrderRepository.save(purchaseOrder);
        saveOperationLog("PURCHASE", "CREATE", "新建采购单：" + saved.getOrderNumber(), saved.getOperatorId());
        return saved;
    }

    @Override
    public PurchaseOrder updatePurchaseOrder(PurchaseOrder purchaseOrder) {
        PurchaseOrder existingOrder = getPurchaseOrderById(purchaseOrder.getId())
                .orElseThrow(() -> new IllegalArgumentException("采购单不存在，无法更新"));
        if ("已入库".equals(existingOrder.getStatus())) {
            throw new IllegalArgumentException("已入库采购单不允许修改");
        }
        existingOrder.setSupplierId(purchaseOrder.getSupplierId());
        existingOrder.setOperatorId(purchaseOrder.getOperatorId());
        existingOrder.setOrderTime(defaultDateTime(purchaseOrder.getOrderTime()));
        existingOrder.setRemark(purchaseOrder.getRemark());
        existingOrder.setItems(copyPurchaseItems(purchaseOrder.getItems()));
        normalizePurchaseOrder(existingOrder, true);
        PurchaseOrder saved = purchaseOrderRepository.save(existingOrder);
        saveOperationLog("PURCHASE", "UPDATE", "更新采购单：" + saved.getOrderNumber(), saved.getOperatorId());
        return saved;
    }

    @Override
    public void deletePurchaseOrder(Long id) {
        PurchaseOrder order = getPurchaseOrderById(id)
                .orElseThrow(() -> new IllegalArgumentException("采购单不存在，无法删除"));
        if ("已入库".equals(order.getStatus())) {
            throw new IllegalArgumentException("已入库采购单不允许删除");
        }
        purchaseOrderRepository.deleteById(id);
        saveOperationLog("PURCHASE", "DELETE", "删除采购单：" + order.getOrderNumber(), order.getOperatorId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PurchaseOrder> getPurchaseOrderById(Long id) {
        return purchaseOrderRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PurchaseOrder> getAllPurchaseOrders(Long supplierId, String status, String dateFrom, String dateTo) {
        LocalDate from = parseDate(dateFrom);
        LocalDate to = parseDate(dateTo);
        return purchaseOrderRepository.findAll().stream()
                .filter(order -> supplierId == null || supplierId.equals(order.getSupplierId()))
                .filter(order -> !StringUtils.hasText(status) || status.equals(order.getStatus()))
                .filter(order -> inDateRange(order.getOrderTime(), from, to))
                .sorted(Comparator.comparing(PurchaseOrder::getOrderTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    @Override
    public PurchaseOrder stockIn(Long id) {
        PurchaseOrder order = getPurchaseOrderById(id)
                .orElseThrow(() -> new IllegalArgumentException("采购单不存在，无法入库"));
        if ("已入库".equals(order.getStatus())) {
            return order;
        }
        for (PurchaseOrderItem item : safePurchaseItems(order.getItems())) {
            if (item == null || item.getProductId() == null) {
                throw new IllegalArgumentException("采购商品不能为空");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new IllegalArgumentException("采购商品数量必须大于 0");
            }
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("商品不存在，无法入库"));
            Inventory inventory = findOrCreateInventory(product.getId(), product.getLocationId(), product.getStock());
            int beforeQuantity = defaultInteger(inventory.getQuantity());
            int addQuantity = defaultInteger(item.getQuantity());
            int afterQuantity = beforeQuantity + addQuantity;
            inventory.setQuantity(afterQuantity);
            if (StringUtils.hasText(item.getLocationId())) {
                inventory.setLocationId(item.getLocationId());
                product.setLocationId(item.getLocationId());
            }
            inventory.setLastUpdateTime(LocalDateTime.now());
            inventoryRepository.save(inventory);

            product.setStock(afterQuantity);
            productRepository.save(product);

            InventoryLog log = new InventoryLog();
            log.setProductId(product.getId());
            log.setProductName(product.getName());
            log.setChangeType("入库");
            log.setQuantity(addQuantity);
            log.setBeforeQuantity(beforeQuantity);
            log.setAfterQuantity(afterQuantity);
            log.setOperatorId(order.getOperatorId());
            log.setRelatedOrderId(order.getId());
            log.setRemark("采购单入库：" + order.getOrderNumber());
            log.setCreateTime(LocalDateTime.now());
            inventoryLogRepository.save(log);
        }
        order.setStatus("已入库");
        PurchaseOrder saved = purchaseOrderRepository.save(order);
        saveOperationLog("PURCHASE", "STOCK_IN", "采购单入库：" + saved.getOrderNumber(), saved.getOperatorId());
        return saved;
    }

    private void normalizePurchaseOrder(PurchaseOrder purchaseOrder, boolean keepOrderNumber) {
        if (purchaseOrder == null) {
            throw new IllegalArgumentException("采购单不能为空");
        }
        if (!keepOrderNumber || !StringUtils.hasText(purchaseOrder.getOrderNumber())) {
            purchaseOrder.setOrderNumber("PO" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()));
        }
        purchaseOrder.setOrderTime(defaultDateTime(purchaseOrder.getOrderTime()));
        if (purchaseOrder.getCreateTime() == null) {
            purchaseOrder.setCreateTime(LocalDateTime.now());
        }
        if (!StringUtils.hasText(purchaseOrder.getStatus())) {
            purchaseOrder.setStatus("待入库");
        }
        List<PurchaseOrderItem> items = copyPurchaseItems(purchaseOrder.getItems());
        if (items.isEmpty()) {
            throw new IllegalArgumentException("采购商品不能为空");
        }
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (PurchaseOrderItem item : items) {
            if (item == null || item.getProductId() == null) {
                throw new IllegalArgumentException("采购商品不能为空");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new IllegalArgumentException("采购商品数量必须大于 0");
            }
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("商品不存在：" + item.getProductId()));
            if (!StringUtils.hasText(item.getProductName())) {
                item.setProductName(product.getName());
            }
            if (item.getPrice() == null) {
                item.setPrice(product.getCostPrice() == null ? BigDecimal.ZERO : product.getCostPrice());
            }
            if (item.getPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("采购商品价格不能小于 0");
            }
            BigDecimal amount = item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            item.setAmount(amount);
            totalAmount = totalAmount.add(amount);
        }
        purchaseOrder.setItems(items);
        purchaseOrder.setTotalAmount(totalAmount);
    }

    private List<PurchaseOrderItem> copyPurchaseItems(List<PurchaseOrderItem> items) {
        return new ArrayList<>(safePurchaseItems(items));
    }

    private List<PurchaseOrderItem> safePurchaseItems(List<PurchaseOrderItem> items) {
        return items == null ? new ArrayList<>() : items;
    }

    private Inventory findOrCreateInventory(Long productId, String fallbackLocation, Integer currentProductStock) {
        return inventoryRepository.findByProductId(productId)
                .orElseGet(() -> {
                    Inventory inventory = new Inventory();
                    inventory.setProductId(productId);
                    inventory.setQuantity(defaultInteger(currentProductStock));
                    inventory.setWarningThreshold(10);
                    inventory.setLocationId(fallbackLocation);
                    inventory.setLastUpdateTime(LocalDateTime.now());
                    return inventoryRepository.save(inventory);
                });
    }

    private boolean inDateRange(LocalDateTime dateTime, LocalDate from, LocalDate to) {
        if (dateTime == null) {
            return true;
        }
        LocalDate date = dateTime.toLocalDate();
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    private LocalDate parseDate(String value) {
        return StringUtils.hasText(value) ? LocalDate.parse(value) : null;
    }

    private LocalDateTime defaultDateTime(LocalDateTime value) {
        return value == null ? LocalDateTime.now() : value;
    }

    private int defaultInteger(Integer value) {
        return value == null ? 0 : value;
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
}
