package com.example.demo.service.impl;

import com.example.demo.entity.OperationLog;
import com.example.demo.entity.Product;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.price.service.ProductPriceHistoryService;
import com.example.demo.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 商品业务逻辑实现类
 */
@Service
@Transactional
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final OperationLogRepository operationLogRepository;
    private final ProductPriceHistoryService priceHistoryService;

    public ProductServiceImpl(ProductRepository productRepository, OperationLogRepository operationLogRepository) {
        this(productRepository, operationLogRepository, null);
    }

    @Autowired
    public ProductServiceImpl(ProductRepository productRepository, OperationLogRepository operationLogRepository,
                              ProductPriceHistoryService priceHistoryService) {
        this.productRepository = productRepository;
        this.operationLogRepository = operationLogRepository;
        this.priceHistoryService = priceHistoryService;
    }

    @Override
    public Product saveProduct(Product product) {
        return saveProduct(product, 1L);
    }

    @Override
    public Product saveProduct(Product product, Long operatorId) {
        fillProductDefaults(product);
        validateProduct(product);
        ensureBarcodeAvailable(product.getBarcode(), null);
        Product saved = productRepository.save(product);
        if (priceHistoryService != null) {
            priceHistoryService.recordInitialPrices(saved, operatorId, "PRODUCT_CREATE",
                    "product-create:" + UUID.randomUUID() + ":" + saved.getId());
        }
        saveOperationLog("PRODUCT", "CREATE", "新增商品：" + saved.getName(), operatorId);
        return saved;
    }

    @Override
    public Product updateProduct(Product product) {
        return updateProduct(product, 1L);
    }

    @Override
    public Product updateProduct(Product product, Long operatorId) {
        Product existingProduct = getProductById(product.getId())
                .orElseThrow(() -> new IllegalArgumentException("商品不存在，无法更新"));
        Product before = priceSnapshot(existingProduct);
        mergeProduct(existingProduct, product);
        fillProductDefaults(existingProduct);
        validateProduct(existingProduct);
        ensureBarcodeAvailable(existingProduct.getBarcode(), existingProduct.getId());
        Product saved = productRepository.save(existingProduct);
        if (priceHistoryService != null) {
            priceHistoryService.recordMasterChanges(before, saved, operatorId, "MANUAL_PRODUCT_EDIT",
                    null, saved.getId(), null,
                    "manual:" + UUID.randomUUID() + ":" + saved.getId());
        }
        saveOperationLog("PRODUCT", "UPDATE", "更新商品：" + saved.getName(), operatorId);
        return saved;
    }

    private Product priceSnapshot(Product value) {
        Product copy = new Product();
        copy.setId(value.getId());
        copy.setCostPrice(value.getCostPrice());
        copy.setRetailPrice(value.getRetailPrice());
        copy.setWholesalePrice(value.getWholesalePrice());
        copy.setOldCustomerPrice(value.getOldCustomerPrice());
        return copy;
    }

    @Override
    public void deleteProduct(Long id) {
        Product product = getProductById(id)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在，无法删除"));
        productRepository.deleteById(id);
        saveOperationLog("PRODUCT", "DELETE", "删除商品：" + product.getName(), 1L);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> getProductById(Long id) {
        return productRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> getAllProducts(String keyword, Integer status) {
        return productRepository.findAll().stream()
                .filter(product -> matchesKeyword(product, keyword))
                .filter(product -> status == null || status.equals(product.getStatus()))
                .collect(Collectors.toList());
    }

    @Override
    public void changeProductStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("商品状态只能是 0 或 1");
        }
        Product product = getProductById(id)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在，无法修改状态"));
        product.setStatus(status);
        productRepository.save(product);
        saveOperationLog("PRODUCT", "UPDATE_STATUS", "修改商品状态：" + product.getName(), 1L);
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
        if (product == null) {
            throw new IllegalArgumentException("商品不能为空");
        }
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

    private void validateProduct(Product product) {
        if (!StringUtils.hasText(product.getName())) {
            throw new IllegalArgumentException("商品名称不能为空");
        }
        if (!StringUtils.hasText(product.getBarcode())) {
            throw new IllegalArgumentException("商品条码不能为空");
        }
        product.setName(product.getName().trim());
        product.setBarcode(product.getBarcode().trim());
        validateNonNegative(product.getRetailPrice(), "零售价");
        validateNonNegative(product.getWholesalePrice(), "批发价");
        validateNonNegative(product.getOldCustomerPrice(), "老客户价");
        validateNonNegative(product.getCostPrice(), "进价");
        if (product.getStock() < 0) {
            throw new IllegalArgumentException("商品库存不能小于 0");
        }
        if (product.getStatus() != 0 && product.getStatus() != 1) {
            throw new IllegalArgumentException("商品状态只能是 0 或 1");
        }
    }

    private void validateNonNegative(BigDecimal value, String fieldName) {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(fieldName + "不能小于 0");
        }
    }

    private void ensureBarcodeAvailable(String barcode, Long currentProductId) {
        productRepository.findByBarcode(barcode).ifPresent(existing -> {
            if (currentProductId == null || !currentProductId.equals(existing.getId())) {
                throw new IllegalArgumentException("商品条码已存在");
            }
        });
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

    private void saveOperationLog(String module, String action, String detail, Long operatorId) {
        OperationLog log = new OperationLog();
        log.setOperatorId(operatorId);
        log.setModule(module);
        log.setAction(action);
        log.setDetail(detail);
        log.setCreateTime(LocalDateTime.now());
        operationLogRepository.save(log);
    }
}
