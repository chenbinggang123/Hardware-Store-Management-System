package com.example.demo.repository;

import com.example.demo.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 库存数据访问接口
 */
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

}