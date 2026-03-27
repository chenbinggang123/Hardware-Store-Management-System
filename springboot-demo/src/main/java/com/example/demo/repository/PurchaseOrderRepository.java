package com.example.demo.repository;

import com.example.demo.entity.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 采购订单数据访问接口
 */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

}