package com.example.demo.controller;

import com.example.demo.common.ApiResponse;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.service.PurchaseOrderService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 采购订单管理接口
 */
@RestController
@RequestMapping("/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    @PostMapping
    public ApiResponse<PurchaseOrder> addPurchaseOrder(@RequestBody PurchaseOrder purchaseOrder) {
        return ApiResponse.ok("采购单新增成功", purchaseOrderService.savePurchaseOrder(purchaseOrder));
    }

    @PutMapping("/{id}")
    public ApiResponse<PurchaseOrder> updatePurchaseOrder(@PathVariable Long id, @RequestBody PurchaseOrder purchaseOrder) {
        purchaseOrder.setId(id);
        return ApiResponse.ok("采购单更新成功", purchaseOrderService.updatePurchaseOrder(purchaseOrder));
    }

    @GetMapping("/{id}")
    public ApiResponse<PurchaseOrder> getPurchaseOrder(@PathVariable Long id) {
        return ApiResponse.ok("采购单详情查询成功", purchaseOrderService.getPurchaseOrderById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "采购单不存在")));
    }

    @GetMapping
    public ApiResponse<List<PurchaseOrder>> getAllPurchaseOrders(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return ApiResponse.ok("采购单列表查询成功",
                purchaseOrderService.getAllPurchaseOrders(supplierId, status, dateFrom, dateTo));
    }

    @PatchMapping("/{id}/stock-in")
    public ApiResponse<PurchaseOrder> stockIn(@PathVariable Long id) {
        return ApiResponse.ok("采购单入库成功", purchaseOrderService.stockIn(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deletePurchaseOrder(@PathVariable Long id) {
        purchaseOrderService.deletePurchaseOrder(id);
        return ApiResponse.ok("采购单删除成功", null);
    }
}
