package com.example.demo.repository;

import com.example.demo.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 供应商数据访问接口
 */
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

}