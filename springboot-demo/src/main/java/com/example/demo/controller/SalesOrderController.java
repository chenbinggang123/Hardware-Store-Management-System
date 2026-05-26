package com.example.demo.controller;

import com.example.demo.common.ApiResponse;
import com.example.demo.dto.PaymentRequest;
import com.example.demo.entity.SalesOrder;
import com.example.demo.service.SalesOrderService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 销售订单管理接口
 */
@RestController
@RequestMapping("/sales-orders")
public class SalesOrderController {

    private final SalesOrderService salesOrderService;

    public SalesOrderController(SalesOrderService salesOrderService) {
        this.salesOrderService = salesOrderService;
    }

    @PostMapping
    public ApiResponse<SalesOrder> addSalesOrder(@RequestBody SalesOrder salesOrder) {
        return ApiResponse.ok("销售单新增成功", salesOrderService.saveSalesOrder(salesOrder));
    }

    @PutMapping("/{id}")
    public ApiResponse<SalesOrder> updateSalesOrder(@PathVariable Long id, @RequestBody SalesOrder salesOrder) {
        salesOrder.setId(id);
        return ApiResponse.ok("销售单更新成功", salesOrderService.updateSalesOrder(salesOrder));
    }

    @GetMapping("/{id}")
    public ApiResponse<SalesOrder> getSalesOrder(@PathVariable Long id) {
        return ApiResponse.ok("销售单详情查询成功", salesOrderService.getSalesOrderById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "销售单不存在")));
    }

    @GetMapping
    public ApiResponse<List<SalesOrder>> getAllSalesOrders(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String payStatus,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return ApiResponse.ok("销售单列表查询成功",
                salesOrderService.getAllSalesOrders(customerId, status, payStatus, dateFrom, dateTo));
    }

    @PatchMapping("/{id}/stock-out")
    public ApiResponse<SalesOrder> stockOut(@PathVariable Long id) {
        return ApiResponse.ok("销售单出库成功", salesOrderService.stockOut(id));
    }

    @PatchMapping("/{id}/payment")
    public ApiResponse<SalesOrder> registerPayment(@PathVariable Long id, @RequestBody PaymentRequest paymentRequest) {
        return ApiResponse.ok("销售单收款登记成功", salesOrderService.registerPayment(id, paymentRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSalesOrder(@PathVariable Long id) {
        salesOrderService.deleteSalesOrder(id);
        return ApiResponse.ok("销售单删除成功", null);
    }
}
