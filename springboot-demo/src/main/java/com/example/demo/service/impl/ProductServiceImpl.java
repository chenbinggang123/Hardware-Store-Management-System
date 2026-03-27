package com.example.demo.service.impl;

import com.example.demo.entity.Product;
import com.example.demo.service.ProductService;
import com.example.demo.service.impl.support.InMemoryCrudStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 商品业务逻辑实现类
 */
@Service
public class ProductServiceImpl implements ProductService {

    private final InMemoryCrudStore<Product> store = new InMemoryCrudStore<>(Product::getId, Product::setId);

    @Autowired
    public ProductServiceImpl() {
        initializeSampleProducts();
    }

    @Override
    public Product saveProduct(Product product) {
        fillProductDefaults(product);
        return store.save(product);
    }

    @Override
    public Product updateProduct(Product product) {
        Product existingProduct = getProductById(product.getId())
                .orElseThrow(() -> new IllegalArgumentException("商品不存在，无法更新"));
        mergeProduct(existingProduct, product);
        fillProductDefaults(existingProduct);
        return store.save(existingProduct);
    }

    @Override
    public void deleteProduct(Long id) {
        store.deleteById(id);
    }

    @Override
    public Optional<Product> getProductById(Long id) {
        return store.findById(id);
    }

    @Override
    public List<Product> getAllProducts(String keyword, Integer status) {
        return store.findAll().stream()
                .filter(product -> matchesKeyword(product, keyword))
                .filter(product -> status == null || status.equals(product.getStatus()))
                .collect(Collectors.toList());
    }

    @Override
    public void changeProductStatus(Long id, Integer status) {
        Product product = getProductById(id)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在，无法修改状态"));
        product.setStatus(status);
        store.save(product);
    }

    private boolean matchesKeyword(Product product, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String normalizedKeyword = keyword.trim().toLowerCase();
        return containsText(product.getName(), normalizedKeyword)
                || containsText(product.getBarcode(), normalizedKeyword)
                || containsText(product.getSpec(), normalizedKeyword)
                || containsText(product.getSourceFactory(), normalizedKeyword);
    }

    private boolean containsText(String value, String keyword) {
        return StringUtils.hasText(value) && value.toLowerCase().contains(keyword);
    }

    private void fillProductDefaults(Product product) {
        if (product.getCreateTime() == null) {
            product.setCreateTime(LocalDateTime.now());
        }
        if (product.getStatus() == null) {
            product.setStatus(1);
        }
        if (product.getStock() == null) {
            product.setStock(0);
        }
    }

    private void mergeProduct(Product target, Product source) {
        target.setName(source.getName());
        target.setBarcode(source.getBarcode());
        target.setSpec(source.getSpec());
        target.setUnit(source.getUnit());
        target.setUnitConvert(source.getUnitConvert());
        target.setRetailPrice(source.getRetailPrice());
        target.setWholesalePrice(source.getWholesalePrice());
        target.setOldCustomerPrice(source.getOldCustomerPrice());
        target.setCostPrice(source.getCostPrice());
        target.setStock(source.getStock());
        target.setLocationId(source.getLocationId());
        target.setSourceFactory(source.getSourceFactory());
        target.setImageUrl(source.getImageUrl());
        if (source.getStatus() != null) {
            target.setStatus(source.getStatus());
        }
    }

    private void initializeSampleProducts() {
        if (!store.isEmpty()) {
            return;
        }

        Product hammer = new Product();
        hammer.setName("羊角锤");
        hammer.setBarcode("690000000001");
        hammer.setSpec("16oz");
        hammer.setUnit("把");
        hammer.setRetailPrice(new BigDecimal("28.00"));
        hammer.setWholesalePrice(new BigDecimal("24.50"));
        hammer.setOldCustomerPrice(new BigDecimal("23.00"));
        hammer.setCostPrice(new BigDecimal("18.80"));
        hammer.setStock(48);
        hammer.setLocationId("A-01");
        hammer.setSourceFactory("宁波五金厂");
        hammer.setStatus(1);
        saveProduct(hammer);

        Product drill = new Product();
        drill.setName("冲击电钻");
        drill.setBarcode("690000000002");
        drill.setSpec("680W");
        drill.setUnit("台");
        drill.setRetailPrice(new BigDecimal("268.00"));
        drill.setWholesalePrice(new BigDecimal("245.00"));
        drill.setOldCustomerPrice(new BigDecimal("238.00"));
        drill.setCostPrice(new BigDecimal("198.00"));
        drill.setStock(12);
        drill.setLocationId("B-03");
        drill.setSourceFactory("江苏机电设备厂");
        drill.setStatus(1);
        saveProduct(drill);

        Product pipe = new Product();
        pipe.setName("PVC水管");
        pipe.setBarcode("690000000003");
        pipe.setSpec("20mm");
        pipe.setUnit("根");
        pipe.setRetailPrice(new BigDecimal("16.00"));
        pipe.setWholesalePrice(new BigDecimal("13.50"));
        pipe.setOldCustomerPrice(new BigDecimal("12.80"));
        pipe.setCostPrice(new BigDecimal("9.60"));
        pipe.setStock(96);
        pipe.setLocationId("C-02");
        pipe.setSourceFactory("佛山建材厂");
        pipe.setStatus(0);
        saveProduct(pipe);
    }
}
