package com.example.demo.repository;

import com.example.demo.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

/**
 * 商品数据访问接口
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByBarcode(String barcode);
    List<Product> findTop10ByNameIgnoreCaseOrderByIdAsc(String name);
    List<Product> findTop10ByNameContainingIgnoreCaseOrderByIdAsc(String name);
}
