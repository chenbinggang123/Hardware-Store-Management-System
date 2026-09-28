package com.example.demo.controller;

import com.example.demo.common.ApiResponse;
import com.example.demo.entity.PriceTrendItem;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.Supplier;
import com.example.demo.service.SupplierService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 供应商管理接口
 */
@RestController
@RequestMapping("/suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @PostMapping
    public ApiResponse<Supplier> addSupplier(@RequestBody Supplier supplier) {
        return ApiResponse.ok("供应商新增成功", supplierService.saveSupplier(supplier));
    }

    @PutMapping("/{id}")
    public ApiResponse<Supplier> updateSupplier(@PathVariable Long id, @RequestBody Supplier supplier) {
        supplier.setId(id);
        return ApiResponse.ok("供应商更新成功", supplierService.updateSupplier(supplier));
    }

    @GetMapping("/{id}")
    public ApiResponse<Supplier> getSupplier(@PathVariable Long id) {
        return ApiResponse.ok("供应商详情查询成功", supplierService.getSupplierById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "供应商不存在")));
    }

    @GetMapping
    public ApiResponse<List<Supplier>> getAllSuppliers(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok("供应商列表查询成功", supplierService.getAllSuppliers(keyword));
    }

    @GetMapping("/{id}/purchase-orders")
    public ApiResponse<List<PurchaseOrder>> getPurchaseOrders(@PathVariable Long id) {
        return ApiResponse.ok("供应商采购历史查询成功", supplierService.getPurchaseOrdersBySupplier(id));
    }

    @GetMapping("/{id}/price-trends")
    public ApiResponse<List<PriceTrendItem>> getPriceTrends(@PathVariable Long id) {
        return ApiResponse.ok("供应商价格趋势查询成功", supplierService.getPriceTrends(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSupplier(@PathVariable Long id) {
        supplierService.deleteSupplier(id);
        return ApiResponse.ok("供应商删除成功", null);
    }
}
