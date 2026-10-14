package com.example.demo.finance.repository;

import com.example.demo.finance.entity.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {
    List<PaymentAllocation> findByDocumentIdOrderByCreateTimeDesc(Long documentId);
    List<PaymentAllocation> findByPaymentIdOrderByCreateTimeAsc(Long paymentId);
}
