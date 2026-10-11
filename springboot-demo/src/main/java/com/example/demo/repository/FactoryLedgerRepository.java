package com.example.demo.repository;

import com.example.demo.entity.FactoryLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FactoryLedgerRepository extends JpaRepository<FactoryLedger, Long> {
    Optional<FactoryLedger> findByFactoryNameIgnoreCase(String factoryName);
}
