package com.example.demo.controller;

import com.example.demo.common.ApiResponse;
import com.example.demo.entity.AccountRecord;
import com.example.demo.entity.Customer;
import com.example.demo.entity.SalesOrder;
import com.example.demo.service.CustomerService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 客户管理接口
 */
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    public ApiResponse<Customer> addCustomer(@RequestBody Customer customer) {
        return ApiResponse.ok("客户新增成功", customerService.saveCustomer(customer));
    }

    @PutMapping("/{id}")
    public ApiResponse<Customer> updateCustomer(@PathVariable Long id, @RequestBody Customer customer) {
        customer.setId(id);
        return ApiResponse.ok("客户更新成功", customerService.updateCustomer(customer));
    }

    @GetMapping("/{id}")
    public ApiResponse<Customer> getCustomer(@PathVariable Long id) {
        return ApiResponse.ok("客户详情查询成功", customerService.getCustomerById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "客户不存在")));
    }

    @GetMapping
    public ApiResponse<List<Customer>> getAllCustomers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type) {
        return ApiResponse.ok("客户列表查询成功", customerService.getAllCustomers(keyword, type));
    }

    @GetMapping("/{id}/sales-orders")
    public ApiResponse<List<SalesOrder>> getSalesOrders(@PathVariable Long id) {
        return ApiResponse.ok("客户订单历史查询成功", customerService.getSalesOrdersByCustomer(id));
    }

    @GetMapping("/{id}/accounts")
    public ApiResponse<List<AccountRecord>> getAccounts(@PathVariable Long id) {
        return ApiResponse.ok("客户账款记录查询成功", customerService.getAccountsByCustomer(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteCustomer(@PathVariable Long id) {
        customerService.deleteCustomer(id);
        return ApiResponse.ok("客户删除成功", null);
    }
}
