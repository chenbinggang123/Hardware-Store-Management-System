package com.example.demo.integration;

import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import com.example.demo.entity.Product;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.PurchaseOrderItem;
import com.example.demo.repository.InventoryLogRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.PurchaseOrderService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PurchaseOrderIntegrationTest {

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryLogRepository inventoryLogRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void purchaseStockInPersistsInventoryProductLogAndIsIdempotent() {
        Product beforeProduct = productRepository.findById(1L).orElseThrow();
        Inventory beforeInventory = inventoryRepository.findByProductId(1L).orElseThrow();
        int productStockBefore = beforeProduct.getStock();
        int inventoryBefore = beforeInventory.getQuantity();
        int logCountBefore = inventoryLogRepository.findByProductIdOrderByCreateTimeDesc(1L).size();

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setProductId(1L);
        item.setQuantity(3);

        PurchaseOrder order = new PurchaseOrder();
        order.setSupplierId(1L);
        order.setOperatorId(1L);
        order.setItems(List.of(item));

        PurchaseOrder created = purchaseOrderService.savePurchaseOrder(order);
        assertNotNull(created.getId());
        assertEquals("待入库", created.getStatus());

        PurchaseOrder firstStockIn = purchaseOrderService.stockIn(created.getId());
        purchaseOrderService.stockIn(created.getId());
        entityManager.flush();
        entityManager.clear();

        assertEquals("已入库", firstStockIn.getStatus());
        assertEquals(productStockBefore + 3, productRepository.findById(1L).orElseThrow().getStock());
        assertEquals(inventoryBefore + 3,
                inventoryRepository.findByProductId(1L).orElseThrow().getQuantity());

        List<InventoryLog> logs = inventoryLogRepository.findByProductIdOrderByCreateTimeDesc(1L);
        assertEquals(logCountBefore + 1, logs.size());
        InventoryLog newestLog = logs.get(0);
        assertEquals("入库", newestLog.getChangeType());
        assertEquals(3, newestLog.getQuantity());
        assertEquals(created.getId(), newestLog.getRelatedOrderId());
    }
}
