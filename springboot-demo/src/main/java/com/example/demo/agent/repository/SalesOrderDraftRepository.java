package com.example.demo.agent.repository;

import com.example.demo.agent.entity.SalesOrderDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SalesOrderDraftRepository extends JpaRepository<SalesOrderDraft, Long> {
    Optional<SalesOrderDraft> findByCommitIdempotencyKey(String commitIdempotencyKey);
}
