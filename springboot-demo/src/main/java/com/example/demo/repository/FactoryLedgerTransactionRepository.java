package com.example.demo.repository;

import com.example.demo.entity.FactoryLedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FactoryLedgerTransactionRepository extends JpaRepository<FactoryLedgerTransaction, Long> {
    List<FactoryLedgerTransaction> findByLedgerIdOrderByTransactionDateDescCreateTimeDesc(Long ledgerId);
    boolean existsByLedgerIdAndReceiptNo(Long ledgerId, String receiptNo);
}
