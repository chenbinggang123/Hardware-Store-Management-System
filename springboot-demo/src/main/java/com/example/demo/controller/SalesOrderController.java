package com.example.demo.controller;

import com.example.demo.entity.SalesOrder;
import com.example.demo.service.SalesOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 销售订单管理接口
 */
@RestController
@RequestMapping("/sales-orders")
public class SalesOrderController {
    @Autowired
    private SalesOrderService salesOrderService;

    // 新增销售订单
    @PostMapping
    public SalesOrder addSalesOrder(@RequestBody SalesOrder salesOrder) {
        return salesOrderService.saveSalesOrder(salesOrder);
    }

    // 编辑销售订单
    @PutMapping("/{id}")
    public SalesOrder updateSalesOrder(@PathVariable Long id, @RequestBody SalesOrder salesOrder) {
        salesOrder.setId(id);
        return salesOrderService.updateSalesOrder(salesOrder);
    }

    // 查询销售订单详情
    @GetMapping("/{id}")
    public Optional<SalesOrder> getSalesOrder(@PathVariable Long id) {
        return salesOrderService.getSalesOrderById(id);
    }

    // 查询销售订单列表
    @GetMapping
    public List<SalesOrder> getAllSalesOrders() {
        return salesOrderService.getAllSalesOrders();
    }

    // 删除销售订单
    @DeleteMapping("/{id}")
    public void deleteSalesOrder(@PathVariable Long id) {
        salesOrderService.deleteSalesOrder(id);
    }
}
