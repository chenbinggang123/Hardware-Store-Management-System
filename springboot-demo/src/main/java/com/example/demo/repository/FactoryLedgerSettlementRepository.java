package com.example.demo.repository;

import com.example.demo.entity.FactoryLedgerSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FactoryLedgerSettlementRepository extends JpaRepository<FactoryLedgerSettlement, Long> {
    Optional<FactoryLedgerSettlement> findByLedgerIdAndSettlementMonth(Long ledgerId, String settlementMonth);
}
