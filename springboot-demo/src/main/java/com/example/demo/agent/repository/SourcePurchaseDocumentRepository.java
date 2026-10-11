package com.example.demo.agent.repository;

import com.example.demo.agent.entity.SourcePurchaseDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourcePurchaseDocumentRepository extends JpaRepository<SourcePurchaseDocument, Long> {
    Optional<SourcePurchaseDocument> findByIdempotencyKey(String idempotencyKey);
    Optional<SourcePurchaseDocument> findBySupplierNameIgnoreCaseAndSourceOrderNo(String supplierName, String sourceOrderNo);
    List<SourcePurchaseDocument> findAllByOrderByCreateTimeDesc();
}
