package com.example.demo.repository;

import com.example.demo.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 商品表数据访问接口
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    // 可根据需要自定义查询方法
}
