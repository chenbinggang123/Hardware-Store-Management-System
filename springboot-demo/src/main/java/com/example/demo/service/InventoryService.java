package com.example.demo.service;

import com.example.demo.entity.Inventory;
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
    List<Inventory> getAllInventories();
}