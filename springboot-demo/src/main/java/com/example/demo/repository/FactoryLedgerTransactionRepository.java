package com.example.demo.repository;

import com.example.demo.entity.FactoryLedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FactoryLedgerTransactionRepository extends JpaRepository<FactoryLedgerTransaction, Long> {
    List<FactoryLedgerTransaction> findByLedgerIdOrderByTransactionDateDescCreateTimeDesc(Long ledgerId);
    boolean existsByLedgerIdAndReceiptNo(Long ledgerId, String receiptNo);
    Optional<FactoryLedgerTransaction> findFirstByReceiptNoAndSource(String receiptNo, String source);
    List<FactoryLedgerTransaction> findByReceiptNoInAndSource(java.util.Collection<String> receiptNos, String source);
    List<FactoryLedgerTransaction> findBySourceAndReceiptNoStartingWith(String source, String receiptNoPrefix);
}
