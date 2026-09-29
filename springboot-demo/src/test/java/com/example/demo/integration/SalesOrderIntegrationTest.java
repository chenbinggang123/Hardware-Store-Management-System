package com.example.demo.integration;

import com.example.demo.dto.PaymentRequest;
import com.example.demo.entity.Customer;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import com.example.demo.entity.Product;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.SalesOrderItem;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.InventoryLogRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.service.SalesOrderService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SalesOrderIntegrationTest {

    @Autowired
    private SalesOrderService salesOrderService;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryLogRepository inventoryLogRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void salesStockOutAndPaymentPersistConsistentlyAndStockOutIsIdempotent() {
        Product beforeProduct = productRepository.findById(1L).orElseThrow();
        Inventory beforeInventory = inventoryRepository.findByProductId(1L).orElseThrow();
        int productStockBefore = beforeProduct.getStock();
        int inventoryBefore = beforeInventory.getQuantity();
        int logCountBefore = inventoryLogRepository.findByProductIdOrderByCreateTimeDesc(1L).size();

        SalesOrderItem item = new SalesOrderItem();
        item.setProductId(1L);
        item.setQuantity(2);

        SalesOrder order = new SalesOrder();
        order.setCustomerId(2L);
        order.setOperatorId(1L);
        order.setReceivedAmount(new BigDecimal("100.00"));
        order.setItems(List.of(item));

        SalesOrder created = salesOrderService.saveSalesOrder(order);
        assertNotNull(created.getId());
        assertEquals(new BigDecimal("690.00"), created.getTotalAmount());
        assertEquals(new BigDecimal("590.00"), created.getDebtAmount());
        assertEquals("部分", created.getPayStatus());

        salesOrderService.stockOut(created.getId());
        salesOrderService.stockOut(created.getId());

        PaymentRequest payment = new PaymentRequest();
        payment.setReceivedAmount(new BigDecimal("90.00"));
        payment.setPaymentMethod("现金");
        SalesOrder paid = salesOrderService.registerPayment(created.getId(), payment);

        entityManager.flush();
        entityManager.clear();

        SalesOrder persisted = salesOrderRepository.findById(created.getId()).orElseThrow();
        assertEquals("已出库", persisted.getStatus());
        assertEquals(new BigDecimal("190.00"), paid.getReceivedAmount());
        assertEquals(new BigDecimal("500.00"), persisted.getDebtAmount());
        assertEquals("部分", persisted.getPayStatus());

        assertEquals(productStockBefore - 2, productRepository.findById(1L).orElseThrow().getStock());
        assertEquals(inventoryBefore - 2,
                inventoryRepository.findByProductId(1L).orElseThrow().getQuantity());

        List<InventoryLog> logs = inventoryLogRepository.findByProductIdOrderByCreateTimeDesc(1L);
        assertEquals(logCountBefore + 1, logs.size());
        InventoryLog newestLog = logs.get(0);
        assertEquals("出库", newestLog.getChangeType());
        assertEquals(2, newestLog.getQuantity());
        assertEquals(created.getId(), newestLog.getRelatedOrderId());

        Customer customer = customerRepository.findById(2L).orElseThrow();
        assertEquals(new BigDecimal("500.00"), customer.getDebt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void stockOutRollsBackAllProductsWhenLaterItemHasInsufficientInventory() {
        int productOneBefore = productRepository.findById(1L).orElseThrow().getStock();
        int inventoryOneBefore = inventoryRepository.findByProductId(1L).orElseThrow().getQuantity();
        int productOneLogCountBefore = inventoryLogRepository
                .findByProductIdOrderByCreateTimeDesc(1L).size();

        SalesOrderItem availableItem = new SalesOrderItem();
        availableItem.setProductId(1L);
        availableItem.setQuantity(1);
        SalesOrderItem insufficientItem = new SalesOrderItem();
        insufficientItem.setProductId(3L);
        insufficientItem.setQuantity(999);

        SalesOrder order = new SalesOrder();
        order.setCustomerId(2L);
        order.setOperatorId(1L);
        order.setItems(List.of(availableItem, insufficientItem));
        SalesOrder created = salesOrderService.saveSalesOrder(order);

        try {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> salesOrderService.stockOut(created.getId()));
            assertTrue(exception.getMessage().contains("库存不足"));

            assertEquals(productOneBefore, productRepository.findById(1L).orElseThrow().getStock());
            assertEquals(inventoryOneBefore,
                    inventoryRepository.findByProductId(1L).orElseThrow().getQuantity());
            assertEquals(productOneLogCountBefore,
                    inventoryLogRepository.findByProductIdOrderByCreateTimeDesc(1L).size());
            assertEquals("待出库", salesOrderRepository.findById(created.getId()).orElseThrow().getStatus());
        } finally {
            salesOrderService.deleteSalesOrder(created.getId());
        }
    }
}
