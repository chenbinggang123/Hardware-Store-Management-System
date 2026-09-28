package com.example.demo.service;

import java.time.LocalDate;
import java.util.Map;

/**
 * 报表统计服务
 */
public interface ReportService {
    Map<String, Object> getDailyReport(LocalDate date);
    Map<String, Object> getMonthlyReport(int year, int month);
    Map<String, Object> getYearlyReport(int year);
    Map<String, Object> getPurchaseReport(LocalDate startDate, LocalDate endDate);
    Map<String, Object> getSalesReport(LocalDate startDate, LocalDate endDate);
    Map<String, Object> getInventoryReport();
    Map<String, Object> getChartReport(LocalDate startDate, LocalDate endDate);
    Map<String, Object> exportReport(String type, LocalDate startDate, LocalDate endDate);
}
