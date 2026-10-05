package com.example.demo.excel.repository;

import com.example.demo.excel.entity.ExcelComparison;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExcelComparisonRepository extends JpaRepository<ExcelComparison, Long> {
    Optional<ExcelComparison> findByOperatorIdAndIdempotencyKey(Long operatorId, String idempotencyKey);
    Optional<ExcelComparison> findByIdAndOperatorId(Long id, Long operatorId);
}
