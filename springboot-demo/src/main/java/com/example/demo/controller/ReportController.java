package com.example.demo.controller;

import com.example.demo.common.ApiResponse;
import com.example.demo.service.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 报表统计接口
 */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/daily")
    public ApiResponse<Map<String, Object>> getDailyReport(@RequestParam(required = false) LocalDate date) {
        return ApiResponse.ok("日报查询成功", reportService.getDailyReport(date));
    }

    @GetMapping("/monthly")
    public ApiResponse<Map<String, Object>> getMonthlyReport(@RequestParam int year, @RequestParam int month) {
        return ApiResponse.ok("月报查询成功", reportService.getMonthlyReport(year, month));
    }

    @GetMapping("/yearly")
    public ApiResponse<Map<String, Object>> getYearlyReport(@RequestParam int year) {
        return ApiResponse.ok("年报查询成功", reportService.getYearlyReport(year));
    }

    @GetMapping("/purchases")
    public ApiResponse<Map<String, Object>> getPurchaseReport(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        return ApiResponse.ok("采购报表查询成功", reportService.getPurchaseReport(startDate, endDate));
    }

    @GetMapping("/sales")
    public ApiResponse<Map<String, Object>> getSalesReport(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        return ApiResponse.ok("销售报表查询成功", reportService.getSalesReport(startDate, endDate));
    }

    @GetMapping("/inventories")
    public ApiResponse<Map<String, Object>> getInventoryReport() {
        return ApiResponse.ok("库存报表查询成功", reportService.getInventoryReport());
    }

    @GetMapping("/charts")
    public ApiResponse<Map<String, Object>> getChartReport(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        return ApiResponse.ok("图表报表查询成功", reportService.getChartReport(startDate, endDate));
    }

    @GetMapping("/export")
    public ApiResponse<Map<String, Object>> exportReport(
            @RequestParam String type,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        return ApiResponse.ok("报表导出结果生成成功", reportService.exportReport(type, startDate, endDate));
    }
}
