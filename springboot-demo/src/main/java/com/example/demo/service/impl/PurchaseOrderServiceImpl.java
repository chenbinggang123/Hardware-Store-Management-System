package com.example.demo.service.impl;

import com.example.demo.entity.PurchaseOrder;
import com.example.demo.service.PurchaseOrderService;
import com.example.demo.service.impl.support.InMemoryCrudStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 采购订单业务逻辑实现类
 */
@Service
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    private final InMemoryCrudStore<PurchaseOrder> store = new InMemoryCrudStore<>(PurchaseOrder::getId, PurchaseOrder::setId);

    @Autowired
    public PurchaseOrderServiceImpl() {
    }

    @Override
    public PurchaseOrder savePurchaseOrder(PurchaseOrder purchaseOrder) {
        if (purchaseOrder.getCreateTime() == null) {
            purchaseOrder.setCreateTime(LocalDateTime.now());
        }
        if (purchaseOrder.getOrderTime() == null) {
            purchaseOrder.setOrderTime(LocalDateTime.now());
        }
        return store.save(purchaseOrder);
    }

    @Override
    public PurchaseOrder updatePurchaseOrder(PurchaseOrder purchaseOrder) {
        PurchaseOrder existingOrder = getPurchaseOrderById(purchaseOrder.getId())
                .orElseThrow(() -> new IllegalArgumentException("采购单不存在，无法更新"));
        existingOrder.setOrderNumber(purchaseOrder.getOrderNumber());
        existingOrder.setSupplierId(purchaseOrder.getSupplierId());
        existingOrder.setOrderTime(purchaseOrder.getOrderTime());
        existingOrder.setTotalAmount(purchaseOrder.getTotalAmount());
        existingOrder.setStatus(purchaseOrder.getStatus());
        return store.save(existingOrder);
    }

    @Override
    public void deletePurchaseOrder(Long id) {
        store.deleteById(id);
    }

    @Override
    public Optional<PurchaseOrder> getPurchaseOrderById(Long id) {
        return store.findById(id);
    }

    @Override
    public List<PurchaseOrder> getAllPurchaseOrders() {
        return store.findAll();
    }
}
