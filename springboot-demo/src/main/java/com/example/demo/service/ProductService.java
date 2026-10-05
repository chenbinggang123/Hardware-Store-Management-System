package com.example.demo.service;

import com.example.demo.entity.Product;
import java.util.List;
import java.util.Optional;

/**
 * 商品业务逻辑接口
 */
public interface ProductService {
    Product saveProduct(Product product);
    default Product saveProduct(Product product, Long operatorId) { return saveProduct(product); }
    Product updateProduct(Product product);
    default Product updateProduct(Product product, Long operatorId) { return updateProduct(product); }
    void deleteProduct(Long id);
    Optional<Product> getProductById(Long id);
    List<Product> getAllProducts(String keyword, Integer status);
    void changeProductStatus(Long id, Integer status);
}
