package com.example.demo.repository;

import com.example.demo.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 客户数据访问接口
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

}