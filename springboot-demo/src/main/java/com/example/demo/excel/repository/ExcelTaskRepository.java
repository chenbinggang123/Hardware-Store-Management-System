package com.example.demo.excel.repository;

import com.example.demo.excel.entity.ExcelTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface ExcelTaskRepository extends JpaRepository<ExcelTask, Long> {
    List<ExcelTask> findByOperatorIdOrderByUpdateTimeDesc(Long operatorId);
    List<ExcelTask> findByOperatorIdAndStatusOrderByUpdateTimeDesc(Long operatorId, String status);
    Optional<ExcelTask> findByIdAndOperatorId(Long id, Long operatorId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from ExcelTask task where task.id = :id and task.operatorId = :operatorId")
    Optional<ExcelTask> findForUpdate(@Param("id") Long id, @Param("operatorId") Long operatorId);
}
