package com.example.demo.finance.repository;

import com.example.demo.finance.entity.AccountPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountPaymentRepository extends JpaRepository<AccountPayment, Long> {
    List<AccountPayment> findByOperatorIdOrderByPaymentDateDescIdDesc(Long operatorId);
}
