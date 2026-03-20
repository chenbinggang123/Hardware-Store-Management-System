package com.example.demo.repository;

import com.example.demo.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 客户表数据访问接口
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    // 可根据需要自定义查询方法
}
