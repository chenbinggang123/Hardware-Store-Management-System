package com.example.demo.service;

import com.example.demo.dto.InventoryAdjustRequest;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import com.example.demo.entity.Product;
import com.example.demo.repository.InventoryLogRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.impl.InventoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryWorkflowTest {

    private InventoryRepository inventoryRepository;
    private InventoryLogRepository inventoryLogRepository;
    private ProductRepository productRepository;
    private OperationLogRepository operationLogRepository;
    private InventoryServiceImpl service;

    @BeforeEach
    void setUp() {
        inventoryRepository = mock(InventoryRepository.class);
        inventoryLogRepository = mock(InventoryLogRepository.class);
        productRepository = mock(ProductRepository.class);
        operationLogRepository = mock(OperationLogRepository.class);
        service = new InventoryServiceImpl(
                inventoryRepository,
                inventoryLogRepository,
                productRepository,
                operationLogRepository);
    }

    @Test
    void adjustSynchronizesProductAndWritesDetailedLog() {
        Inventory inventory = inventory(10L, 1L, 10, 3);
        Product product = product(1L, "冲击钻", 10);
        when(inventoryRepository.findById(10L)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(any(Inventory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        InventoryAdjustRequest request = new InventoryAdjustRequest();
        request.setActualQuantity(7);
        request.setOperatorId(2L);
        request.setReason("盘亏复核");

        Inventory adjusted = service.adjustInventory(10L, request);

        assertEquals(7, adjusted.getQuantity());
        assertEquals(7, product.getStock());
        verify(productRepository).save(product);

        ArgumentCaptor<InventoryLog> logCaptor = ArgumentCaptor.forClass(InventoryLog.class);
        verify(inventoryLogRepository).save(logCaptor.capture());
        InventoryLog log = logCaptor.getValue();
        assertEquals("盘点", log.getChangeType());
        assertEquals(-3, log.getQuantity());
        assertEquals(10, log.getBeforeQuantity());
        assertEquals(7, log.getAfterQuantity());
        assertEquals(2L, log.getOperatorId());
        assertEquals("盘亏复核", log.getRemark());
    }

    @Test
    void adjustRejectsMissingOrNegativeActualQuantity() {
        Inventory inventory = inventory(10L, 1L, 10, 3);
        when(inventoryRepository.findById(10L)).thenReturn(Optional.of(inventory));

        assertThrows(IllegalArgumentException.class, () -> service.adjustInventory(10L, null));

        InventoryAdjustRequest missing = new InventoryAdjustRequest();
        assertThrows(IllegalArgumentException.class, () -> service.adjustInventory(10L, missing));

        InventoryAdjustRequest negative = new InventoryAdjustRequest();
        negative.setActualQuantity(-1);
        assertThrows(IllegalArgumentException.class, () -> service.adjustInventory(10L, negative));

        verify(inventoryRepository, never()).save(any());
        verify(inventoryLogRepository, never()).save(any());
        verify(productRepository, never()).save(any());
    }

    @Test
    void createRejectsUnknownProductDuplicateAndInvalidNumbers() {
        Inventory unknown = inventory(null, 999L, 1, 1);
        when(productRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.saveInventory(unknown));

        Inventory duplicate = inventory(null, 1L, 1, 1);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "冲击钻", 1)));
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory(10L, 1L, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> service.saveInventory(duplicate));

        Inventory negativeQuantity = inventory(null, 2L, -1, 1);
        when(productRepository.findById(2L)).thenReturn(Optional.of(product(2L, "扳手", 0)));
        assertThrows(IllegalArgumentException.class, () -> service.saveInventory(negativeQuantity));

        Inventory negativeThreshold = inventory(null, 3L, 1, -1);
        when(productRepository.findById(3L)).thenReturn(Optional.of(product(3L, "胶布", 1)));
        assertThrows(IllegalArgumentException.class, () -> service.saveInventory(negativeThreshold));

        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void warningsIncludeThresholdBoundaryOnly() {
        Inventory below = inventory(1L, 1L, 2, 3);
        Inventory boundary = inventory(2L, 2L, 3, 3);
        Inventory healthy = inventory(3L, 3L, 4, 3);
        when(productRepository.findAll()).thenReturn(List.of());
        when(inventoryRepository.findAll()).thenReturn(List.of(below, boundary, healthy));

        List<Inventory> warnings = service.getWarnings();

        assertEquals(List.of(below, boundary), warnings);
        assertTrue(warnings.stream().noneMatch(item -> item.getId().equals(3L)));
    }

    private Inventory inventory(Long id, Long productId, int quantity, int threshold) {
        Inventory inventory = new Inventory();
        inventory.setId(id);
        inventory.setProductId(productId);
        inventory.setQuantity(quantity);
        inventory.setWarningThreshold(threshold);
        inventory.setLocationId("A-01");
        return inventory;
    }

    private Product product(Long id, String name, int stock) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setStock(stock);
        return product;
    }
}
