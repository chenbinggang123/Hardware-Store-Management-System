package com.example.demo.service.impl;

import com.example.demo.entity.SalesOrder;
import com.example.demo.service.SalesOrderService;
import com.example.demo.service.impl.support.InMemoryCrudStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 销售订单业务逻辑实现类
 */
@Service
public class SalesOrderServiceImpl implements SalesOrderService {

    private final InMemoryCrudStore<SalesOrder> store = new InMemoryCrudStore<>(SalesOrder::getId, SalesOrder::setId);

    @Autowired
    public SalesOrderServiceImpl() {
    }

    @Override
    public SalesOrder saveSalesOrder(SalesOrder salesOrder) {
        if (salesOrder.getCreateTime() == null) {
            salesOrder.setCreateTime(LocalDateTime.now());
        }
        if (salesOrder.getOrderTime() == null) {
            salesOrder.setOrderTime(LocalDateTime.now());
        }
        return store.save(salesOrder);
    }

    @Override
    public SalesOrder updateSalesOrder(SalesOrder salesOrder) {
        SalesOrder existingOrder = getSalesOrderById(salesOrder.getId())
                .orElseThrow(() -> new IllegalArgumentException("销售单不存在，无法更新"));
        existingOrder.setOrderNumber(salesOrder.getOrderNumber());
        existingOrder.setCustomerId(salesOrder.getCustomerId());
        existingOrder.setOrderTime(salesOrder.getOrderTime());
        existingOrder.setTotalAmount(salesOrder.getTotalAmount());
        existingOrder.setStatus(salesOrder.getStatus());
        return store.save(existingOrder);
    }

    @Override
    public void deleteSalesOrder(Long id) {
        store.deleteById(id);
    }

    @Override
    public Optional<SalesOrder> getSalesOrderById(Long id) {
        return store.findById(id);
    }

    @Override
    public List<SalesOrder> getAllSalesOrders() {
        return store.findAll();
    }
}
