package com.example.demo.agent.repository;

import com.example.demo.agent.entity.AgentConversationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentConversationRunRepository extends JpaRepository<AgentConversationRun, Long> {
    List<AgentConversationRun> findByConversationIdOrderByCreateTimeAsc(Long conversationId);

    Optional<AgentConversationRun> findByRunId(Long runId);

    void deleteByConversationId(Long conversationId);
}
