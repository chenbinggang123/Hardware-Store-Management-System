package com.example.demo.service;

import com.example.demo.entity.Customer;
import java.util.List;
import java.util.Optional;

/**
 * 客户业务逻辑接口
 */
public interface CustomerService {
    Customer saveCustomer(Customer customer);
    Customer updateCustomer(Customer customer);
    void deleteCustomer(Long id);
    Optional<Customer> getCustomerById(Long id);
    List<Customer> getAllCustomers();
}