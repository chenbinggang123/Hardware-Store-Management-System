package com.example.demo.repository;

import com.example.demo.entity.SalesOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 销售单表数据访问接口
 */
@Repository
public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {
    // 可根据需要自定义查询方法
}
