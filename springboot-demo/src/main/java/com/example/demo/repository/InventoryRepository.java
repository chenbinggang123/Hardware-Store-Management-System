package com.example.demo.repository;

import com.example.demo.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 库存表数据访问接口
 */
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    // 可根据需要自定义查询方法
}
