package com.example.demo.controller;

import com.example.demo.entity.Product;
import com.example.demo.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 商品管理接口
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {
    @Autowired
    private ProductService productService;

    // 新增商品
    @PostMapping
    public Product addProduct(@RequestBody Product product) {
        return productService.saveProduct(product);
    }

    // 编辑商品
    @PutMapping("/{id}")
    public Product updateProduct(@PathVariable Long id, @RequestBody Product product) {
        product.setId(id);
        return productService.updateProduct(product);
    }

    // 查询商品详情
    @GetMapping("/{id}")
    public Optional<Product> getProduct(@PathVariable Long id) {
        return productService.getProductById(id);
    }

    // 查询商品列表
    @GetMapping
    public List<Product> getAllProducts() {
        return productService.getAllProducts();
    }

    // 删除商品
    @DeleteMapping("/{id}")
    public void deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
    }

    // 商品上下架
    @PatchMapping("/{id}/status")
    public void changeProductStatus(@PathVariable Long id, @RequestParam Integer status) {
        productService.changeProductStatus(id, status);
    }

    // 图片管理接口（示例：仅保存图片URL，实际可扩展为文件上传）
    @PatchMapping("/{id}/image")
    public Product updateProductImage(@PathVariable Long id, @RequestParam String imageUrl) {
        Optional<Product> optional = productService.getProductById(id);
        if (optional.isPresent()) {
            Product product = optional.get();
            product.setImageUrl(imageUrl);
            return productService.updateProduct(product);
        }
        throw new RuntimeException("商品不存在");
    }
}
