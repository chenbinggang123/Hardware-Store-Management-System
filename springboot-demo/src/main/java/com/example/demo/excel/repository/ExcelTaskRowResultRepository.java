package com.example.demo.excel.repository;

import com.example.demo.excel.entity.ExcelTaskRowResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExcelTaskRowResultRepository extends JpaRepository<ExcelTaskRowResult, Long> {
    Page<ExcelTaskRowResult> findByTaskIdAndSheetIdOrderByRowNumberAsc(Long taskId, Long sheetId, Pageable pageable);
    void deleteByTaskIdAndSheetId(Long taskId, Long sheetId);
    long countByTaskIdAndSheetIdAndMatchStatus(Long taskId, Long sheetId, String matchStatus);
    Optional<ExcelTaskRowResult> findByIdAndTaskIdAndSheetId(Long id, Long taskId, Long sheetId);
    List<ExcelTaskRowResult> findByTaskIdOrderBySheetIdAscRowNumberAsc(Long taskId);
}
