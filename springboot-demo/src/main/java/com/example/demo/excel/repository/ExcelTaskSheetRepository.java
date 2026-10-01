package com.example.demo.excel.repository;

import com.example.demo.excel.entity.ExcelTaskSheet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExcelTaskSheetRepository extends JpaRepository<ExcelTaskSheet, Long> {
    List<ExcelTaskSheet> findByTaskIdOrderBySheetIndexAsc(Long taskId);
    Optional<ExcelTaskSheet> findByIdAndTaskId(Long id, Long taskId);
}
