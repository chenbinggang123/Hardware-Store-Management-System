package com.example.demo.service;

import com.example.demo.entity.Product;
import java.util.List;
import java.util.Optional;

/**
 * 商品业务逻辑接口
 */
public interface ProductService {
    Product saveProduct(Product product);
    Product updateProduct(Product product);
    void deleteProduct(Long id);
    Optional<Product> getProductById(Long id);
    List<Product> getAllProducts();
    void changeProductStatus(Long id, Integer status);
}
