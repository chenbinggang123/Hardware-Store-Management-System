package com.example.demo.repository;

import com.example.demo.entity.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 操作日志数据访问接口
 */
public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
}
