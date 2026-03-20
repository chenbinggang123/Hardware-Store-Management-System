package com.example.demo.repository;

import com.example.demo.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 供应商表数据访问接口
 */
@Repository
public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    // 可根据需要自定义查询方法
}
