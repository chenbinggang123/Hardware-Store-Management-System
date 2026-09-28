package com.example.demo.controller;

import com.example.demo.common.ApiResponse;
import com.example.demo.entity.Product;
import com.example.demo.service.ProductService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 商品管理接口
 */
@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ApiResponse<Product> addProduct(@RequestBody Product product) {
        return ApiResponse.ok("商品新增成功", productService.saveProduct(product));
    }

    @PutMapping("/{id}")
    public ApiResponse<Product> updateProduct(@PathVariable Long id, @RequestBody Product product) {
        product.setId(id);
        return ApiResponse.ok("商品更新成功", productService.updateProduct(product));
    }

    @GetMapping("/{id}")
    public ApiResponse<Product> getProduct(@PathVariable Long id) {
        return ApiResponse.ok("商品详情查询成功", productService.getProductById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "商品不存在")));
    }

    @GetMapping
    public ApiResponse<List<Product>> getAllProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return ApiResponse.ok("商品列表查询成功", productService.getAllProducts(keyword, status));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ApiResponse.ok("商品删除成功", null);
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<Void> changeProductStatus(@PathVariable Long id, @RequestParam Integer status) {
        productService.changeProductStatus(id, status);
        return ApiResponse.ok("商品状态更新成功", null);
    }

    @PatchMapping("/{id}/image")
    public ApiResponse<Product> updateProductImage(@PathVariable Long id, @RequestParam String imageUrl) {
        Product product = productService.getProductById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "商品不存在"));
        product.setImageUrl(imageUrl);
        return ApiResponse.ok("商品图片更新成功", productService.updateProduct(product));
    }
}
