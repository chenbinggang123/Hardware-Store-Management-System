package com.example.demo.service;

import com.example.demo.entity.SalesOrder;
import java.util.List;
import java.util.Optional;

/**
 * 销售订单业务逻辑接口
 */
public interface SalesOrderService {
    SalesOrder saveSalesOrder(SalesOrder salesOrder);
    SalesOrder updateSalesOrder(SalesOrder salesOrder);
    void deleteSalesOrder(Long id);
    Optional<SalesOrder> getSalesOrderById(Long id);
    List<SalesOrder> getAllSalesOrders();
}