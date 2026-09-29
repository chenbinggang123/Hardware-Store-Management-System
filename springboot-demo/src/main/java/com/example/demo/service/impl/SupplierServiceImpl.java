package com.example.demo.service.impl;

import com.example.demo.entity.OperationLog;
import com.example.demo.entity.PriceTrendItem;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.PurchaseOrderItem;
import com.example.demo.entity.Supplier;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.PurchaseOrderRepository;
import com.example.demo.repository.SupplierRepository;
import com.example.demo.service.SupplierService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 供应商业务逻辑实现类
 */
@Service
@Transactional
public class SupplierServiceImpl implements SupplierService {

    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final OperationLogRepository operationLogRepository;

    public SupplierServiceImpl(
            SupplierRepository supplierRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            OperationLogRepository operationLogRepository) {
        this.supplierRepository = supplierRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.operationLogRepository = operationLogRepository;
    }

    @Override
    public Supplier saveSupplier(Supplier supplier) {
        validateSupplier(supplier);
        if (supplier.getCreateTime() == null) {
            supplier.setCreateTime(LocalDateTime.now());
        }
        Supplier saved = supplierRepository.save(supplier);
        saveOperationLog("SUPPLIER", "CREATE", "新增供应商：" + saved.getName(), 1L);
        return saved;
    }

    @Override
    public Supplier updateSupplier(Supplier supplier) {
        validateSupplier(supplier);
        Supplier existingSupplier = getSupplierById(supplier.getId())
                .orElseThrow(() -> new IllegalArgumentException("供应商不存在，无法更新"));
        existingSupplier.setName(supplier.getName());
        existingSupplier.setContact(supplier.getContact());
        existingSupplier.setPhone(supplier.getPhone());
        existingSupplier.setAddress(supplier.getAddress());
        existingSupplier.setRemark(supplier.getRemark());
        Supplier saved = supplierRepository.save(existingSupplier);
        saveOperationLog("SUPPLIER", "UPDATE", "更新供应商：" + saved.getName(), 1L);
        return saved;
    }

    @Override
    public void deleteSupplier(Long id) {
        Supplier supplier = getSupplierById(id)
                .orElseThrow(() -> new IllegalArgumentException("供应商不存在，无法删除"));
        if (!purchaseOrderRepository.findBySupplierId(id).isEmpty()) {
            throw new IllegalArgumentException("供应商存在采购订单，不允许删除");
        }
        supplierRepository.deleteById(id);
        saveOperationLog("SUPPLIER", "DELETE", "删除供应商：" + supplier.getName(), 1L);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Supplier> getSupplierById(Long id) {
        return supplierRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Supplier> getAllSuppliers(String keyword) {
        return supplierRepository.findAll().stream()
                .filter(supplier -> matchesKeyword(supplier, keyword))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PurchaseOrder> getPurchaseOrdersBySupplier(Long supplierId) {
        return purchaseOrderRepository.findBySupplierId(supplierId).stream()
                .sorted(Comparator.comparing(PurchaseOrder::getOrderTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PriceTrendItem> getPriceTrends(Long supplierId) {
        List<PriceTrendItem> items = new ArrayList<>();
        for (PurchaseOrder order : getPurchaseOrdersBySupplier(supplierId)) {
            if (order.getItems() == null) {
                continue;
            }
            for (PurchaseOrderItem item : order.getItems()) {
                PriceTrendItem trendItem = new PriceTrendItem();
                trendItem.setSupplierId(supplierId);
                trendItem.setProductId(item.getProductId());
                trendItem.setProductName(item.getProductName());
                trendItem.setPrice(item.getPrice());
                trendItem.setOrderNumber(order.getOrderNumber());
                trendItem.setOrderTime(order.getOrderTime());
                items.add(trendItem);
            }
        }
        items.sort(Comparator.comparing(PriceTrendItem::getOrderTime, Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    private boolean matchesKeyword(Supplier supplier, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String normalized = keyword.trim().toLowerCase();
        return containsText(supplier.getName(), normalized)
                || containsText(supplier.getContact(), normalized)
                || containsText(supplier.getPhone(), normalized);
    }

    private void validateSupplier(Supplier supplier) {
        if (supplier == null || !StringUtils.hasText(supplier.getName())) {
            throw new IllegalArgumentException("供应商名称不能为空");
        }
        supplier.setName(supplier.getName().trim());
    }

    private boolean containsText(String value, String keyword) {
        return StringUtils.hasText(value) && value.toLowerCase().contains(keyword);
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
