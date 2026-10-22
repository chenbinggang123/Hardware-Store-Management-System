package com.example.demo.repository;

import com.example.demo.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 库存数据访问接口
 */
public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select i from Inventory i where i.id=:id")
    Optional<Inventory> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select i from Inventory i where i.productId=:productId")
    Optional<Inventory> lockByProductId(@org.springframework.data.repository.query.Param("productId") Long productId);
    Optional<Inventory> findByProductId(Long productId);
}
