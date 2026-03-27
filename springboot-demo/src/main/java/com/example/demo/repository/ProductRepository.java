package com.example.demo.repository;

import com.example.demo.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 商品数据访问接口
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

}