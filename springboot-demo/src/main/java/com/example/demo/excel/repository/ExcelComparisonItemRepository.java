package com.example.demo.excel.repository;

import com.example.demo.excel.entity.ExcelComparisonItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExcelComparisonItemRepository extends JpaRepository<ExcelComparisonItem, Long> {
    Page<ExcelComparisonItem> findByComparisonIdOrderByIdAsc(Long comparisonId, Pageable pageable);
    Page<ExcelComparisonItem> findByComparisonIdAndChangeTypeOrderByIdAsc(
            Long comparisonId, String changeType, Pageable pageable);
}
