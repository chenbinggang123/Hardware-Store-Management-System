package com.example.demo.service.impl;

import com.example.demo.entity.Customer;
import com.example.demo.service.CustomerService;
import com.example.demo.service.impl.support.InMemoryCrudStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 客户业务逻辑实现类
 */
@Service
public class CustomerServiceImpl implements CustomerService {

    private final InMemoryCrudStore<Customer> store = new InMemoryCrudStore<>(Customer::getId, Customer::setId);

    @Autowired
    public CustomerServiceImpl() {
    }

    @Override
    public Customer saveCustomer(Customer customer) {
        if (customer.getCreateTime() == null) {
            customer.setCreateTime(LocalDateTime.now());
        }
        return store.save(customer);
    }

    @Override
    public Customer updateCustomer(Customer customer) {
        Customer existingCustomer = getCustomerById(customer.getId())
                .orElseThrow(() -> new IllegalArgumentException("客户不存在，无法更新"));
        existingCustomer.setName(customer.getName());
        existingCustomer.setType(customer.getType());
        existingCustomer.setPhone(customer.getPhone());
        existingCustomer.setAddress(customer.getAddress());
        existingCustomer.setRemark(customer.getRemark());
        return store.save(existingCustomer);
    }

    @Override
    public void deleteCustomer(Long id) {
        store.deleteById(id);
    }

    @Override
    public Optional<Customer> getCustomerById(Long id) {
        return store.findById(id);
    }

    @Override
    public List<Customer> getAllCustomers() {
        return store.findAll();
    }
}
