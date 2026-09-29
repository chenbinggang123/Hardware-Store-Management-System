package com.example.demo.service;

import com.example.demo.entity.Customer;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import com.example.demo.entity.Product;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.SalesOrderItem;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.InventoryLogRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.service.impl.SalesOrderServiceImpl;
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

class SalesOrderWorkflowTest {

    private SalesOrderRepository salesOrderRepository;
    private ProductRepository productRepository;
    private InventoryRepository inventoryRepository;
    private CustomerRepository customerRepository;
    private InventoryLogRepository inventoryLogRepository;
    private OperationLogRepository operationLogRepository;
    private SalesOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        salesOrderRepository = mock(SalesOrderRepository.class);
        productRepository = mock(ProductRepository.class);
        inventoryRepository = mock(InventoryRepository.class);
        customerRepository = mock(CustomerRepository.class);
        inventoryLogRepository = mock(InventoryLogRepository.class);
        operationLogRepository = mock(OperationLogRepository.class);
        service = new SalesOrderServiceImpl(
                salesOrderRepository,
                productRepository,
                inventoryRepository,
                customerRepository,
                inventoryLogRepository,
                operationLogRepository);
    }

    @Test
    void createUsesWholesalePriceAndRecalculatesAmounts() {
        Customer customer = customer(1L, "B");
        Product product = product(1L, "冲击钻", 10, 1);
        SalesOrderItem item = salesItem(1L, 3);
        item.setAmount(new BigDecimal("0.01"));
        SalesOrder order = salesOrder(100L, "待出库", item);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(salesOrderRepository.save(any(SalesOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(salesOrderRepository.findByCustomerId(1L)).thenReturn(List.of(order));

        SalesOrder saved = service.saveSalesOrder(order);

        assertEquals(new BigDecimal("80.00"), saved.getItems().get(0).getPrice());
        assertEquals(new BigDecimal("240.00"), saved.getItems().get(0).getAmount());
        assertEquals(new BigDecimal("240.00"), saved.getTotalAmount());
        assertEquals(new BigDecimal("240.00"), saved.getDebtAmount());
        assertEquals("未付", saved.getPayStatus());
    }

    @Test
    void stockOutUpdatesInventoryProductAndLogExactlyOnce() {
        Product product = product(1L, "冲击钻", 10, 1);
        Inventory inventory = inventory(1L, 1L, 10);
        SalesOrder order = salesOrder(100L, "待出库", salesItem(1L, 2));
        when(salesOrderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));
        when(salesOrderRepository.save(any(SalesOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SalesOrder stockedOut = service.stockOut(100L);

        assertEquals("已出库", stockedOut.getStatus());
        assertEquals(8, inventory.getQuantity());
        assertEquals(8, product.getStock());
        ArgumentCaptor<InventoryLog> logCaptor = ArgumentCaptor.forClass(InventoryLog.class);
        verify(inventoryLogRepository).save(logCaptor.capture());
        assertEquals("出库", logCaptor.getValue().getChangeType());
        assertEquals(10, logCaptor.getValue().getBeforeQuantity());
        assertEquals(2, logCaptor.getValue().getQuantity());
        assertEquals(8, logCaptor.getValue().getAfterQuantity());

        clearInvocations(inventoryRepository, productRepository, inventoryLogRepository,
                salesOrderRepository, operationLogRepository);
        service.stockOut(100L);

        assertEquals(8, inventory.getQuantity());
        verify(inventoryRepository, never()).save(any());
        verify(productRepository, never()).save(any());
        verify(inventoryLogRepository, never()).save(any());
        verify(salesOrderRepository, never()).save(any());
    }

    @Test
    void stockOutRejectsInsufficientInventoryBeforeWriting() {
        Product product = product(1L, "冲击钻", 1, 1);
        Inventory inventory = inventory(1L, 1L, 1);
        SalesOrder order = salesOrder(100L, "待出库", salesItem(1L, 2));
        when(salesOrderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.stockOut(100L));

        assertTrue(exception.getMessage().contains("库存不足"));
        assertEquals(1, inventory.getQuantity());
        verify(inventoryRepository, never()).save(any());
        verify(productRepository, never()).save(any());
        verify(inventoryLogRepository, never()).save(any());
        verify(salesOrderRepository, never()).save(any());
    }

    @Test
    void createRejectsEmptyItems() {
        SalesOrder order = salesOrder(100L, "待出库");
        assertThrows(IllegalArgumentException.class, () -> service.saveSalesOrder(order));
        verify(salesOrderRepository, never()).save(any());
    }

    @Test
    void createRejectsNonPositiveQuantity() {
        SalesOrder order = salesOrder(100L, "待出库", salesItem(1L, 0));
        assertThrows(IllegalArgumentException.class, () -> service.saveSalesOrder(order));
        verify(salesOrderRepository, never()).save(any());
    }

    @Test
    void createRejectsUnknownOrInactiveProduct() {
        SalesOrder unknownOrder = salesOrder(100L, "待出库", salesItem(999L, 1));
        when(productRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.saveSalesOrder(unknownOrder));

        Product inactive = product(1L, "冲击钻", 10, 0);
        SalesOrder inactiveOrder = salesOrder(101L, "待出库", salesItem(1L, 1));
        when(productRepository.findById(1L)).thenReturn(Optional.of(inactive));
        assertThrows(IllegalArgumentException.class, () -> service.saveSalesOrder(inactiveOrder));

        verify(salesOrderRepository, never()).save(any());
    }

    @Test
    void changingOrderCustomerRecalculatesBothCustomersDebt() {
        Customer oldCustomer = customer(1L, "零售");
        oldCustomer.setDebt(new BigDecimal("100.00"));
        Customer newCustomer = customer(2L, "零售");
        Product product = product(1L, "冲击钻", 10, 1);

        SalesOrder existing = salesOrder(100L, "待出库", salesItem(1L, 1));
        existing.setCustomerId(1L);
        existing.setDebtAmount(new BigDecimal("100.00"));
        SalesOrder update = salesOrder(100L, "待出库", salesItem(1L, 1));
        update.setCustomerId(2L);

        when(salesOrderRepository.findById(100L)).thenReturn(Optional.of(existing));
        when(customerRepository.findById(1L)).thenReturn(Optional.of(oldCustomer));
        when(customerRepository.findById(2L)).thenReturn(Optional.of(newCustomer));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(salesOrderRepository.save(any(SalesOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(salesOrderRepository.findByCustomerId(1L)).thenReturn(List.of());
        when(salesOrderRepository.findByCustomerId(2L)).thenReturn(List.of(existing));

        service.updateSalesOrder(update);

        assertEquals(BigDecimal.ZERO, oldCustomer.getDebt());
        assertEquals(new BigDecimal("100.00"), newCustomer.getDebt());
        verify(customerRepository).save(oldCustomer);
        verify(customerRepository).save(newCustomer);
    }

    private Product product(Long id, String name, int stock, int status) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setStock(stock);
        product.setRetailPrice(new BigDecimal("100.00"));
        product.setWholesalePrice(new BigDecimal("80.00"));
        product.setOldCustomerPrice(new BigDecimal("90.00"));
        product.setLocationId("A-01");
        product.setStatus(status);
        return product;
    }

    private Customer customer(Long id, String type) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setType(type);
        customer.setDebt(BigDecimal.ZERO);
        return customer;
    }

    private Inventory inventory(Long id, Long productId, int quantity) {
        Inventory inventory = new Inventory();
        inventory.setId(id);
        inventory.setProductId(productId);
        inventory.setQuantity(quantity);
        return inventory;
    }

    private SalesOrder salesOrder(Long id, String status, SalesOrderItem... items) {
        SalesOrder order = new SalesOrder();
        order.setId(id);
        order.setOrderNumber("SO-" + id);
        order.setCustomerId(1L);
        order.setOperatorId(1L);
        order.setStatus(status);
        order.setItems(List.of(items));
        return order;
    }

    private SalesOrderItem salesItem(Long productId, int quantity) {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }
}
