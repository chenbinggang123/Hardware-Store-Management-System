package com.example.demo.excel.repository;

import com.example.demo.excel.entity.ExcelTaskOutput;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExcelTaskOutputRepository extends JpaRepository<ExcelTaskOutput, Long> {
    Optional<ExcelTaskOutput> findByOperatorIdAndIdempotencyKey(Long operatorId, String idempotencyKey);
    Optional<ExcelTaskOutput> findByIdAndTaskIdAndOperatorId(Long id, Long taskId, Long operatorId);
    List<ExcelTaskOutput> findByTaskIdAndOperatorIdOrderByCreateTimeDesc(Long taskId, Long operatorId);
}
