package com.example.demo.service.impl.support;

import com.example.demo.entity.AppUser;
import com.example.demo.entity.Customer;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import com.example.demo.entity.OperationLog;
import com.example.demo.entity.Product;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.Supplier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 应用内共享内存存储，便于多模块联动。
 */
@Component
public class ApplicationMemoryStore {

    private final InMemoryCrudStore<Product> products = new InMemoryCrudStore<>(Product::getId, Product::setId);
    private final InMemoryCrudStore<Supplier> suppliers = new InMemoryCrudStore<>(Supplier::getId, Supplier::setId);
    private final InMemoryCrudStore<Customer> customers = new InMemoryCrudStore<>(Customer::getId, Customer::setId);
    private final InMemoryCrudStore<PurchaseOrder> purchaseOrders = new InMemoryCrudStore<>(PurchaseOrder::getId, PurchaseOrder::setId);
    private final InMemoryCrudStore<SalesOrder> salesOrders = new InMemoryCrudStore<>(SalesOrder::getId, SalesOrder::setId);
    private final InMemoryCrudStore<Inventory> inventories = new InMemoryCrudStore<>(Inventory::getId, Inventory::setId);
    private final InMemoryCrudStore<InventoryLog> inventoryLogs = new InMemoryCrudStore<>(InventoryLog::getId, InventoryLog::setId);
    private final InMemoryCrudStore<AppUser> users = new InMemoryCrudStore<>(AppUser::getId, AppUser::setId);
    private final InMemoryCrudStore<OperationLog> operationLogs = new InMemoryCrudStore<>(OperationLog::getId, OperationLog::setId);

    public InMemoryCrudStore<Product> products() {
        return products;
    }

    public InMemoryCrudStore<Supplier> suppliers() {
        return suppliers;
    }

    public InMemoryCrudStore<Customer> customers() {
        return customers;
    }

    public InMemoryCrudStore<PurchaseOrder> purchaseOrders() {
        return purchaseOrders;
    }

    public InMemoryCrudStore<SalesOrder> salesOrders() {
        return salesOrders;
    }

    public InMemoryCrudStore<Inventory> inventories() {
        return inventories;
    }

    public InMemoryCrudStore<InventoryLog> inventoryLogs() {
        return inventoryLogs;
    }

    public InMemoryCrudStore<AppUser> users() {
        return users;
    }

    public InMemoryCrudStore<OperationLog> operationLogs() {
        return operationLogs;
    }

    public OperationLog addOperationLog(Long operatorId, String module, String action, String detail) {
        OperationLog log = new OperationLog();
        log.setOperatorId(operatorId);
        log.setModule(module);
        log.setAction(action);
        log.setDetail(detail);
        log.setCreateTime(LocalDateTime.now());
        return operationLogs.save(log);
    }
}
