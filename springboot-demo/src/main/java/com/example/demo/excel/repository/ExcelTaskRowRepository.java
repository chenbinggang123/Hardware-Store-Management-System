package com.example.demo.excel.repository;

import com.example.demo.excel.entity.ExcelTaskRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExcelTaskRowRepository extends JpaRepository<ExcelTaskRow, Long> {
    Page<ExcelTaskRow> findByTaskIdAndSheetIdOrderByRowNumberAsc(Long taskId, Long sheetId, Pageable pageable);
    List<ExcelTaskRow> findByTaskIdAndSheetIdOrderByRowNumberAsc(Long taskId, Long sheetId);
    Optional<ExcelTaskRow> findByIdAndTaskIdAndSheetId(Long id, Long taskId, Long sheetId);
}
