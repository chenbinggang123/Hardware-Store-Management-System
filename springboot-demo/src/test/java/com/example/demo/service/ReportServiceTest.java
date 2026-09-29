package com.example.demo.service;

import com.example.demo.entity.Inventory;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.SalesOrder;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.PurchaseOrderRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.service.impl.ReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private PurchaseOrderRepository purchaseOrderRepository;
    private SalesOrderRepository salesOrderRepository;
    private InventoryRepository inventoryRepository;
    private ReportServiceImpl service;

    @BeforeEach
    void setUp() {
        purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        salesOrderRepository = mock(SalesOrderRepository.class);
        inventoryRepository = mock(InventoryRepository.class);
        service = new ReportServiceImpl(
                purchaseOrderRepository, salesOrderRepository, inventoryRepository);
    }

    @Test
    void salesReportIncludesBothDateBoundariesAndCalculatesAmounts() {
        LocalDate target = LocalDate.of(2026, 3, 27);
        SalesOrder start = salesOrder(target.atStartOfDay(), "100.00", "20.00");
        SalesOrder end = salesOrder(target.atTime(23, 59, 59), "50.00", "50.00");
        SalesOrder nextDay = salesOrder(target.plusDays(1).atStartOfDay(), "999.00", "999.00");
        when(salesOrderRepository.findAll()).thenReturn(List.of(nextDay, end, start));

        Map<String, Object> report = service.getSalesReport(target, target);

        assertEquals(2, report.get("count"));
        assertEquals(new BigDecimal("150.00"), report.get("totalAmount"));
        assertEquals(new BigDecimal("70.00"), report.get("receivedAmount"));
        assertEquals(List.of(start, end), report.get("orders"));
    }

    @Test
    void dailyMonthlyAndYearlyUseExclusiveUpperBoundary() {
        SalesOrder march = salesOrder(LocalDateTime.of(2026, 3, 31, 23, 59), "10.00", "0");
        SalesOrder april = salesOrder(LocalDateTime.of(2026, 4, 1, 0, 0), "20.00", "0");
        when(salesOrderRepository.findAll()).thenReturn(List.of(march, april));
        when(purchaseOrderRepository.findAll()).thenReturn(List.of());
        when(inventoryRepository.findAll()).thenReturn(List.of());

        assertEquals(new BigDecimal("10.00"), service.getDailyReport(LocalDate.of(2026, 3, 31)).get("sales"));
        assertEquals(new BigDecimal("10.00"), service.getMonthlyReport(2026, 3).get("sales"));
        assertEquals(new BigDecimal("30.00"), service.getYearlyReport(2026).get("sales"));
    }

    @Test
    void invalidDatesAndPeriodValuesAreRejected() {
        LocalDate later = LocalDate.of(2026, 4, 1);
        LocalDate earlier = LocalDate.of(2026, 3, 1);

        assertThrows(IllegalArgumentException.class, () -> service.getSalesReport(later, earlier));
        assertThrows(IllegalArgumentException.class, () -> service.getPurchaseReport(later, earlier));
        assertThrows(IllegalArgumentException.class, () -> service.getChartReport(later, earlier));
        assertThrows(IllegalArgumentException.class, () -> service.getMonthlyReport(2026, 13));
        assertThrows(IllegalArgumentException.class, () -> service.getYearlyReport(0));
    }

    @Test
    void inventoryReportCountsThresholdBoundary() {
        Inventory warning = inventory(1, 10);
        Inventory boundary = inventory(10, 10);
        Inventory healthy = inventory(11, 10);
        when(inventoryRepository.findAll()).thenReturn(List.of(warning, boundary, healthy));

        Map<String, Object> report = service.getInventoryReport();

        assertEquals(3, report.get("totalInventoryRecords"));
        assertEquals(2L, report.get("warningCount"));
    }

    @Test
    void exportContainsRealReportAndRejectsUnknownType() {
        LocalDate date = LocalDate.of(2026, 3, 27);
        when(salesOrderRepository.findAll()).thenReturn(List.of(
                salesOrder(date.atTime(12, 0), "100.00", "40.00")));

        Map<String, Object> export = service.exportReport("sales", date, date);

        assertEquals("sales", export.get("type"));
        assertTrue(export.get("report") instanceof Map);
        Map<?, ?> report = (Map<?, ?>) export.get("report");
        assertEquals(1, report.get("count"));
        assertEquals(new BigDecimal("100.00"), report.get("totalAmount"));
        assertThrows(IllegalArgumentException.class,
                () -> service.exportReport("unknown", date, date));
    }

    private SalesOrder salesOrder(LocalDateTime time, String total, String received) {
        SalesOrder order = new SalesOrder();
        order.setOrderTime(time);
        order.setTotalAmount(new BigDecimal(total));
        order.setReceivedAmount(new BigDecimal(received));
        return order;
    }

    private Inventory inventory(int quantity, int threshold) {
        Inventory inventory = new Inventory();
        inventory.setQuantity(quantity);
        inventory.setWarningThreshold(threshold);
        return inventory;
    }
}
