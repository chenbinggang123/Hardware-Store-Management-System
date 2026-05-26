package com.example.demo.service;

import com.example.demo.dto.InventoryAdjustRequest;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import java.util.List;
import java.util.Optional;

/**
 * 库存业务逻辑接口
 */
public interface InventoryService {
    Inventory saveInventory(Inventory inventory);
    Inventory updateInventory(Inventory inventory);
    void deleteInventory(Long id);
    Optional<Inventory> getInventoryById(Long id);
    List<Inventory> getAllInventories(String keyword, String locationId, Boolean warningOnly);
    Inventory adjustInventory(Long id, InventoryAdjustRequest request);
    List<InventoryLog> getInventoryLogs(Long id);
    List<Inventory> getWarnings();
}
