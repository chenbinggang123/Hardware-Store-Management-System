package com.example.demo.service;

import com.example.demo.dto.PaymentRequest;
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
    List<SalesOrder> getAllSalesOrders(Long customerId, String status, String payStatus, String dateFrom, String dateTo);
    SalesOrder stockOut(Long id);
    SalesOrder registerPayment(Long id, PaymentRequest paymentRequest);
}
