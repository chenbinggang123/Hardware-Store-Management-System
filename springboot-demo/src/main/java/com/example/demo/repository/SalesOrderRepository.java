package com.example.demo.repository;

import com.example.demo.entity.SalesOrder;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 销售订单数据访问接口
 */
public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

}