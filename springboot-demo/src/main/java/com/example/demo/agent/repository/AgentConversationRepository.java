package com.example.demo.agent.repository;

import com.example.demo.agent.entity.AgentConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentConversationRepository extends JpaRepository<AgentConversation, Long> {
    List<AgentConversation> findTop50ByOperatorIdOrderByUpdateTimeDesc(Long operatorId);

    Optional<AgentConversation> findByIdAndOperatorId(Long id, Long operatorId);
}
