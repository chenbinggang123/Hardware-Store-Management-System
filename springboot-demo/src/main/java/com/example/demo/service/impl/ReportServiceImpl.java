package com.example.demo.service.impl;

import com.example.demo.entity.Inventory;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.SalesOrder;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.PurchaseOrderRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.service.ReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 报表统计服务实现
 */
@Service
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final InventoryRepository inventoryRepository;

    public ReportServiceImpl(
            PurchaseOrderRepository purchaseOrderRepository,
            SalesOrderRepository salesOrderRepository,
            InventoryRepository inventoryRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Override
    public Map<String, Object> getDailyReport(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now() : date;
        return buildSummary(target.atStartOfDay(), target.plusDays(1).atStartOfDay(), "日报");
    }

    @Override
    public Map<String, Object> getMonthlyReport(int year, int month) {
        validateYear(year);
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("月份必须在 1 到 12 之间");
        }
        YearMonth yearMonth = YearMonth.of(year, month);
        return buildSummary(yearMonth.atDay(1).atStartOfDay(), yearMonth.plusMonths(1).atDay(1).atStartOfDay(), "月报");
    }

    @Override
    public Map<String, Object> getYearlyReport(int year) {
        validateYear(year);
        return buildSummary(LocalDate.of(year, 1, 1).atStartOfDay(), LocalDate.of(year + 1, 1, 1).atStartOfDay(), "年报");
    }

    @Override
    public Map<String, Object> getPurchaseReport(LocalDate startDate, LocalDate endDate) {
        validateDateRange(startDate, endDate);
        return buildPurchaseSection(startDate, endDate);
    }

    @Override
    public Map<String, Object> getSalesReport(LocalDate startDate, LocalDate endDate) {
        validateDateRange(startDate, endDate);
        return buildSalesSection(startDate, endDate);
    }

    @Override
    public Map<String, Object> getInventoryReport() {
        List<Inventory> inventories = inventoryRepository.findAll();
        long warningCount = inventories.stream()
                .filter(inventory -> (inventory.getQuantity() == null ? 0 : inventory.getQuantity())
                        <= (inventory.getWarningThreshold() == null ? 0 : inventory.getWarningThreshold()))
                .count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalInventoryRecords", inventories.size());
        result.put("warningCount", warningCount);
        result.put("inventoryItems", inventories);
        return result;
    }

    @Override
    public Map<String, Object> getChartReport(LocalDate startDate, LocalDate endDate) {
        validateDateRange(startDate, endDate);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sales", buildSalesSection(startDate, endDate));
        result.put("purchases", buildPurchaseSection(startDate, endDate));
        result.put("inventories", getInventoryReport());
        return result;
    }

    @Override
    public Map<String, Object> exportReport(String type, LocalDate startDate, LocalDate endDate) {
        validateDateRange(startDate, endDate);
        if (!StringUtils.hasText(type)) {
            throw new IllegalArgumentException("导出类型不能为空");
        }
        String normalizedType = type.trim().toLowerCase();
        LocalDate referenceDate = startDate == null ? LocalDate.now() : startDate;
        Map<String, Object> report = switch (normalizedType) {
            case "daily" -> getDailyReport(referenceDate);
            case "monthly" -> getMonthlyReport(referenceDate.getYear(), referenceDate.getMonthValue());
            case "sales" -> getSalesReport(startDate, endDate);
            case "purchases" -> getPurchaseReport(startDate, endDate);
            case "inventories" -> getInventoryReport();
            default -> throw new IllegalArgumentException("不支持的导出类型：" + type);
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", normalizedType);
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        result.put("generatedAt", LocalDateTime.now());
        result.put("data", report.toString());
        result.put("report", report);
        return result;
    }

    private Map<String, Object> buildSummary(LocalDateTime start, LocalDateTime end, String label) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("start", start);
        result.put("end", end);
        result.put("purchase", summarizePurchaseAmount(filterPurchaseOrders(start, end)));
        result.put("sales", summarizeSalesAmount(filterSalesOrders(start, end)));
        result.put("inventoryWarnings", getInventoryReport().get("warningCount"));
        return result;
    }

    private Map<String, Object> buildPurchaseSection(LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate == null ? LocalDate.MIN.atStartOfDay() : startDate.atStartOfDay();
        LocalDateTime end = toExclusiveEnd(endDate);
        List<PurchaseOrder> orders = filterPurchaseOrders(start, end);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", orders.size());
        result.put("totalAmount", summarizePurchaseAmount(orders));
        result.put("orders", orders);
        return result;
    }

    private Map<String, Object> buildSalesSection(LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate == null ? LocalDate.MIN.atStartOfDay() : startDate.atStartOfDay();
        LocalDateTime end = toExclusiveEnd(endDate);
        List<SalesOrder> orders = filterSalesOrders(start, end);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", orders.size());
        result.put("totalAmount", summarizeSalesAmount(orders));
        result.put("receivedAmount", orders.stream()
                .map(order -> order.getReceivedAmount() == null ? BigDecimal.ZERO : order.getReceivedAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        result.put("orders", orders);
        return result;
    }

    private List<PurchaseOrder> filterPurchaseOrders(LocalDateTime start, LocalDateTime end) {
        return purchaseOrderRepository.findAll().stream()
                .filter(order -> order.getOrderTime() != null)
                .filter(order -> !order.getOrderTime().isBefore(start) && order.getOrderTime().isBefore(end))
                .sorted(Comparator.comparing(PurchaseOrder::getOrderTime))
                .collect(Collectors.toList());
    }

    private List<SalesOrder> filterSalesOrders(LocalDateTime start, LocalDateTime end) {
        return salesOrderRepository.findAll().stream()
                .filter(order -> order.getOrderTime() != null)
                .filter(order -> !order.getOrderTime().isBefore(start) && order.getOrderTime().isBefore(end))
                .sorted(Comparator.comparing(SalesOrder::getOrderTime))
                .collect(Collectors.toList());
    }

    private BigDecimal summarizePurchaseAmount(List<PurchaseOrder> orders) {
        return orders.stream()
                .map(order -> order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal summarizeSalesAmount(List<SalesOrder> orders) {
        return orders.stream()
                .map(order -> order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
    }

    private void validateYear(int year) {
        if (year < 1 || year > 9998) {
            throw new IllegalArgumentException("年份必须在 1 到 9998 之间");
        }
    }

    private LocalDateTime toExclusiveEnd(LocalDate endDate) {
        if (endDate == null || LocalDate.MAX.equals(endDate)) {
            return LocalDateTime.MAX;
        }
        return endDate.plusDays(1).atStartOfDay();
    }
}
