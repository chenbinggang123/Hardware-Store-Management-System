package com.example.demo.service;

import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import com.example.demo.entity.Product;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.PurchaseOrderItem;
import com.example.demo.repository.InventoryLogRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.PurchaseOrderRepository;
import com.example.demo.service.impl.PurchaseOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchaseOrderWorkflowTest {

    private PurchaseOrderRepository purchaseOrderRepository;
    private ProductRepository productRepository;
    private InventoryRepository inventoryRepository;
    private InventoryLogRepository inventoryLogRepository;
    private OperationLogRepository operationLogRepository;
    private PurchaseOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        productRepository = mock(ProductRepository.class);
        inventoryRepository = mock(InventoryRepository.class);
        inventoryLogRepository = mock(InventoryLogRepository.class);
        operationLogRepository = mock(OperationLogRepository.class);
        service = new PurchaseOrderServiceImpl(
                purchaseOrderRepository,
                productRepository,
                inventoryRepository,
                inventoryLogRepository,
                operationLogRepository);
    }

    @Test
    void stockInUpdatesInventoryProductAndLogExactlyOnce() {
        Product product = product(1L, "冲击钻", 10);
        Inventory inventory = inventory(1L, 1L, 10);
        PurchaseOrder order = purchaseOrder(100L, "待入库", purchaseItem(1L, 2));

        when(purchaseOrderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));
        when(purchaseOrderRepository.save(any(PurchaseOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PurchaseOrder stockedIn = service.stockIn(100L);

        assertEquals("已入库", stockedIn.getStatus());
        assertEquals(12, inventory.getQuantity());
        assertEquals(12, product.getStock());
        verify(inventoryRepository).save(inventory);
        verify(productRepository).save(product);

        ArgumentCaptor<InventoryLog> logCaptor = ArgumentCaptor.forClass(InventoryLog.class);
        verify(inventoryLogRepository).save(logCaptor.capture());
        InventoryLog log = logCaptor.getValue();
        assertEquals("入库", log.getChangeType());
        assertEquals(10, log.getBeforeQuantity());
        assertEquals(2, log.getQuantity());
        assertEquals(12, log.getAfterQuantity());
        assertEquals(100L, log.getRelatedOrderId());

        clearInvocations(inventoryRepository, productRepository, inventoryLogRepository,
                purchaseOrderRepository, operationLogRepository);

        PurchaseOrder repeated = service.stockIn(100L);

        assertEquals(12, repeated.getItems().isEmpty() ? inventory.getQuantity() : product.getStock());
        verify(inventoryRepository, never()).save(any());
        verify(productRepository, never()).save(any());
        verify(inventoryLogRepository, never()).save(any());
        verify(purchaseOrderRepository, never()).save(any());
        verify(operationLogRepository, never()).save(any());
    }

    @Test
    void createRejectsEmptyItems() {
        PurchaseOrder order = purchaseOrder(100L, "待入库");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.savePurchaseOrder(order));

        assertTrue(exception.getMessage().contains("采购商品"));
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void createRejectsNonPositiveQuantity() {
        PurchaseOrder order = purchaseOrder(100L, "待入库", purchaseItem(1L, 0));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.savePurchaseOrder(order));

        assertTrue(exception.getMessage().contains("数量"));
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void createRejectsUnknownProduct() {
        PurchaseOrder order = purchaseOrder(100L, "待入库", purchaseItem(999L, 2));
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.savePurchaseOrder(order));

        assertTrue(exception.getMessage().contains("商品不存在"));
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void createRecalculatesLineAmountAndOrderTotal() {
        Product product = product(1L, "冲击钻", 10);
        PurchaseOrderItem item = purchaseItem(1L, 3);
        item.setPrice(new BigDecimal("88.50"));
        item.setAmount(new BigDecimal("0.01"));
        PurchaseOrder order = purchaseOrder(100L, "待入库", item);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(purchaseOrderRepository.save(any(PurchaseOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PurchaseOrder saved = service.savePurchaseOrder(order);

        assertEquals(new BigDecimal("265.50"), saved.getItems().get(0).getAmount());
        assertEquals(new BigDecimal("265.50"), saved.getTotalAmount());
    }

    @Test
    void createRejectsNegativePrice() {
        Product product = product(1L, "冲击钻", 10);
        PurchaseOrderItem item = purchaseItem(1L, 1);
        item.setPrice(new BigDecimal("-1.00"));
        PurchaseOrder order = purchaseOrder(100L, "待入库", item);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.savePurchaseOrder(order));

        assertTrue(exception.getMessage().contains("价格"));
        verify(purchaseOrderRepository, never()).save(any());
    }

    private Product product(Long id, String name, int stock) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setStock(stock);
        product.setCostPrice(new BigDecimal("100.00"));
        product.setLocationId("A-01");
        return product;
    }

    private Inventory inventory(Long id, Long productId, int quantity) {
        Inventory inventory = new Inventory();
        inventory.setId(id);
        inventory.setProductId(productId);
        inventory.setQuantity(quantity);
        inventory.setLocationId("A-01");
        return inventory;
    }

    private PurchaseOrder purchaseOrder(Long id, String status, PurchaseOrderItem... items) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(id);
        order.setOrderNumber("PO-" + id);
        order.setSupplierId(1L);
        order.setOperatorId(1L);
        order.setStatus(status);
        order.setItems(List.of(items));
        return order;
    }

    private PurchaseOrderItem purchaseItem(Long productId, int quantity) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }
}
