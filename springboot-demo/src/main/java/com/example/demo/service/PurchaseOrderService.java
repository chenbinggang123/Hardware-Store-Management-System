package com.example.demo.service;

import com.example.demo.entity.PurchaseOrder;
import java.util.List;
import java.util.Optional;

/**
 * 采购订单业务逻辑接口
 */
public interface PurchaseOrderService {
    PurchaseOrder savePurchaseOrder(PurchaseOrder purchaseOrder);
    PurchaseOrder updatePurchaseOrder(PurchaseOrder purchaseOrder);
    void deletePurchaseOrder(Long id);
    Optional<PurchaseOrder> getPurchaseOrderById(Long id);
    List<PurchaseOrder> getAllPurchaseOrders();
}