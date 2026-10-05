package com.example.demo.price.repository;

import com.example.demo.price.entity.ProductPriceHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductPriceHistoryRepository extends JpaRepository<ProductPriceHistory, Long> {
    Optional<ProductPriceHistory> findByIdempotencyKey(String idempotencyKey);
    Page<ProductPriceHistory> findByProductIdOrderByCreateTimeDescIdDesc(Long productId, Pageable pageable);
    Page<ProductPriceHistory> findBySupplierIdOrderByCreateTimeDescIdDesc(Long supplierId, Pageable pageable);
    Page<ProductPriceHistory> findBySupplierIdAndProductIdOrderByCreateTimeDescIdDesc(
            Long supplierId, Long productId, Pageable pageable);
}
