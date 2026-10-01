package com.example.demo.excel.repository;

import com.example.demo.excel.entity.ExcelTaskMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExcelTaskMappingRepository extends JpaRepository<ExcelTaskMapping, Long> {
    List<ExcelTaskMapping> findByTaskIdAndSheetIdOrderBySourceColumnIndexAsc(Long taskId, Long sheetId);
    void deleteByTaskIdAndSheetId(Long taskId, Long sheetId);
    @Query("select count(distinct mapping.sheetId) from ExcelTaskMapping mapping where mapping.taskId = :taskId")
    long countMappedSheets(@Param("taskId") Long taskId);
}
