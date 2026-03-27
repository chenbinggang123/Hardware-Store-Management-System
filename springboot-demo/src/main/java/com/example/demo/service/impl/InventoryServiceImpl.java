package com.example.demo.service.impl;

import com.example.demo.entity.Inventory;
import com.example.demo.service.InventoryService;
import com.example.demo.service.impl.support.InMemoryCrudStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 库存业务逻辑实现类
 */
@Service
public class InventoryServiceImpl implements InventoryService {

    private final InMemoryCrudStore<Inventory> store = new InMemoryCrudStore<>(Inventory::getId, Inventory::setId);

    @Autowired
    public InventoryServiceImpl() {
    }

    @Override
    public Inventory saveInventory(Inventory inventory) {
        if (inventory.getLastUpdateTime() == null) {
            inventory.setLastUpdateTime(LocalDateTime.now());
        }
        return store.save(inventory);
    }

    @Override
    public Inventory updateInventory(Inventory inventory) {
        Inventory existingInventory = getInventoryById(inventory.getId())
                .orElseThrow(() -> new IllegalArgumentException("库存记录不存在，无法更新"));
        existingInventory.setProductId(inventory.getProductId());
        existingInventory.setQuantity(inventory.getQuantity());
        existingInventory.setLocationId(inventory.getLocationId());
        existingInventory.setLastUpdateTime(LocalDateTime.now());
        return store.save(existingInventory);
    }

    @Override
    public void deleteInventory(Long id) {
        store.deleteById(id);
    }

    @Override
    public Optional<Inventory> getInventoryById(Long id) {
        return store.findById(id);
    }

    @Override
    public List<Inventory> getAllInventories() {
        return store.findAll();
    }
}
