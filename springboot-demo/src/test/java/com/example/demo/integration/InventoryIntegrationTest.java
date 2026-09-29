package com.example.demo.integration;

import com.example.demo.dto.InventoryAdjustRequest;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import com.example.demo.entity.Product;
import com.example.demo.repository.InventoryLogRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.InventoryService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryLogRepository inventoryLogRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void adjustmentPersistsToInventoryProductLogAndWarningList() {
        Inventory before = inventoryRepository.findByProductId(3L).orElseThrow();
        int beforeQuantity = before.getQuantity();
        int logCountBefore = inventoryLogRepository.findByProductIdOrderByCreateTimeDesc(3L).size();

        InventoryAdjustRequest request = new InventoryAdjustRequest();
        request.setActualQuantity(4);
        request.setOperatorId(1L);
        request.setReason("集成测试盘点");

        inventoryService.adjustInventory(before.getId(), request);
        entityManager.flush();
        entityManager.clear();

        Inventory persistedInventory = inventoryRepository.findByProductId(3L).orElseThrow();
        Product persistedProduct = productRepository.findById(3L).orElseThrow();
        assertEquals(4, persistedInventory.getQuantity());
        assertEquals(4, persistedProduct.getStock());

        List<InventoryLog> logs = inventoryLogRepository.findByProductIdOrderByCreateTimeDesc(3L);
        assertEquals(logCountBefore + 1, logs.size());
        InventoryLog newestLog = logs.get(0);
        assertEquals("盘点", newestLog.getChangeType());
        assertEquals(beforeQuantity, newestLog.getBeforeQuantity());
        assertEquals(4 - beforeQuantity, newestLog.getQuantity());
        assertEquals(4, newestLog.getAfterQuantity());
        assertEquals("集成测试盘点", newestLog.getRemark());

        List<Inventory> warnings = inventoryService.getWarnings();
        assertTrue(warnings.stream().anyMatch(item -> item.getProductId().equals(3L)));
    }
}
