package com.example.demo.repository;

import com.example.demo.entity.InventoryLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 库存日志数据访问接口
 */
public interface InventoryLogRepository extends JpaRepository<InventoryLog, Long> {
    List<InventoryLog> findByProductIdOrderByCreateTimeDesc(Long productId);
}
