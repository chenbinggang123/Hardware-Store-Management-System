package com.example.demo.agent.repository;

import com.example.demo.agent.entity.AgentAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentAttachmentRepository extends JpaRepository<AgentAttachment, Long> {
    List<AgentAttachment> findByOperatorIdOrderByUpdateTimeDesc(Long operatorId);
    Optional<AgentAttachment> findByIdAndOperatorId(Long id, Long operatorId);
    List<AgentAttachment> findByIdInAndOperatorId(List<Long> ids, Long operatorId);
    List<AgentAttachment> findByRunIdOrderByCreateTimeAsc(Long runId);
    List<AgentAttachment> findByConversationIdOrderByCreateTimeAsc(Long conversationId);
    List<AgentAttachment> findByOperatorIdAndObjectKeyContainingAndCreateTimeBefore(Long operatorId, String keyPart, java.time.LocalDateTime before);
}
