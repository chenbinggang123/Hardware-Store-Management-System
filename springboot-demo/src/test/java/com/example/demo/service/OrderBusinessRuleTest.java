package com.example.demo.service;

import com.example.demo.dto.PaymentRequest;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.SalesOrder;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.InventoryLogRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.PurchaseOrderRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.service.impl.PurchaseOrderServiceImpl;
import com.example.demo.service.impl.SalesOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderBusinessRuleTest {

    private PurchaseOrderRepository purchaseOrderRepository;
    private SalesOrderRepository salesOrderRepository;
    private OperationLogRepository operationLogRepository;
    private PurchaseOrderServiceImpl purchaseOrderService;
    private SalesOrderServiceImpl salesOrderService;

    @BeforeEach
    void setUp() {
        purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        salesOrderRepository = mock(SalesOrderRepository.class);
        operationLogRepository = mock(OperationLogRepository.class);

        purchaseOrderService = new PurchaseOrderServiceImpl(
                purchaseOrderRepository,
                mock(ProductRepository.class),
                mock(InventoryRepository.class),
                mock(InventoryLogRepository.class),
                operationLogRepository);
        salesOrderService = new SalesOrderServiceImpl(
                salesOrderRepository,
                mock(ProductRepository.class),
                mock(InventoryRepository.class),
                mock(CustomerRepository.class),
                mock(InventoryLogRepository.class),
                operationLogRepository);
    }

    @Test
    void cannotDeleteStockedInPurchaseOrder() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(10L);
        order.setOrderNumber("PO-10");
        order.setStatus("已入库");
        when(purchaseOrderRepository.findById(10L)).thenReturn(Optional.of(order));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> purchaseOrderService.deletePurchaseOrder(10L));

        assertTrue(exception.getMessage().contains("已入库"));
        verify(purchaseOrderRepository, never()).deleteById(10L);
    }

    @Test
    void cannotDeleteStockedOutSalesOrder() {
        SalesOrder order = new SalesOrder();
        order.setId(20L);
        order.setOrderNumber("SO-20");
        order.setStatus("已出库");
        when(salesOrderRepository.findById(20L)).thenReturn(Optional.of(order));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> salesOrderService.deleteSalesOrder(20L));

        assertTrue(exception.getMessage().contains("已出库"));
        verify(salesOrderRepository, never()).deleteById(20L);
    }

    @Test
    void paymentRejectsNegativeAmount() {
        SalesOrder order = new SalesOrder();
        order.setId(20L);
        order.setOrderNumber("SO-20");
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setReceivedAmount(new BigDecimal("20.00"));
        when(salesOrderRepository.findById(20L)).thenReturn(Optional.of(order));

        PaymentRequest request = new PaymentRequest();
        request.setReceivedAmount(new BigDecimal("-10.00"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> salesOrderService.registerPayment(20L, request));

        assertTrue(exception.getMessage().contains("收款金额"));
        verify(salesOrderRepository, never()).save(order);
    }
}
