package com.example.demo.controller;

import com.example.demo.common.ApiResponse;
import com.example.demo.dto.InventoryAdjustRequest;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import com.example.demo.service.InventoryService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 库存管理接口
 */
@RestController
@RequestMapping("/inventories")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping
    public ApiResponse<Inventory> addInventory(@RequestBody Inventory inventory) {
        return ApiResponse.ok("库存记录新增成功", inventoryService.saveInventory(inventory));
    }

    @PutMapping("/{id}")
    public ApiResponse<Inventory> updateInventory(@PathVariable Long id, @RequestBody Inventory inventory) {
        inventory.setId(id);
        return ApiResponse.ok("库存记录更新成功", inventoryService.updateInventory(inventory));
    }

    @GetMapping("/{id}")
    public ApiResponse<Inventory> getInventory(@PathVariable Long id) {
        return ApiResponse.ok("库存详情查询成功", inventoryService.getInventoryById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "库存记录不存在")));
    }

    @GetMapping
    public ApiResponse<List<Inventory>> getAllInventories(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String locationId,
            @RequestParam(required = false) Boolean warningOnly) {
        return ApiResponse.ok("库存列表查询成功", inventoryService.getAllInventories(keyword, locationId, warningOnly));
    }

    @PatchMapping("/{id}/adjust")
    public ApiResponse<Inventory> adjustInventory(@PathVariable Long id, @RequestBody InventoryAdjustRequest request) {
        return ApiResponse.ok("库存调整成功", inventoryService.adjustInventory(id, request));
    }

    @GetMapping("/{id}/logs")
    public ApiResponse<List<InventoryLog>> getInventoryLogs(@PathVariable Long id) {
        return ApiResponse.ok("库存日志查询成功", inventoryService.getInventoryLogs(id));
    }

    @GetMapping("/warnings")
    public ApiResponse<List<Inventory>> getWarnings() {
        return ApiResponse.ok("库存预警查询成功", inventoryService.getWarnings());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteInventory(@PathVariable Long id) {
        inventoryService.deleteInventory(id);
        return ApiResponse.ok("库存记录删除成功", null);
    }
}
