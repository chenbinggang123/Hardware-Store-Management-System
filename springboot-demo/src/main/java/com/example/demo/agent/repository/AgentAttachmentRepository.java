package com.example.demo.agent.repository;

import com.example.demo.agent.entity.AgentAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentAttachmentRepository extends JpaRepository<AgentAttachment, Long> {
    List<AgentAttachment> findByIdInAndOperatorId(List<Long> ids, Long operatorId);
    List<AgentAttachment> findByRunIdOrderByCreateTimeAsc(Long runId);
    List<AgentAttachment> findByConversationIdOrderByCreateTimeAsc(Long conversationId);
}
