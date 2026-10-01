package com.example.demo.excel.repository;

import com.example.demo.excel.entity.ExcelTaskCommit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExcelTaskCommitRepository extends JpaRepository<ExcelTaskCommit, Long> {
    Optional<ExcelTaskCommit> findByOperatorIdAndIdempotencyKey(Long operatorId, String idempotencyKey);
    Optional<ExcelTaskCommit> findByTaskId(Long taskId);
}
