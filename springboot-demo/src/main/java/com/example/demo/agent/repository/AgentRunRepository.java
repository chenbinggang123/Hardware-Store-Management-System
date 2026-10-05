package com.example.demo.agent.repository;

import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.harness.AgentRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AgentRun r set r.status = :running where r.id = :runId "
            + "and r.operatorId = :operatorId and r.status = :waiting and r.expiresAt > :now")
    int claimApproval(
            @Param("runId") Long runId,
            @Param("operatorId") Long operatorId,
            @Param("waiting") AgentRunStatus waiting,
            @Param("running") AgentRunStatus running,
            @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AgentRun r set r.outputText = :output where r.id = :runId and r.status = :running")
    int updateRunningOutput(
            @Param("runId") Long runId,
            @Param("output") String output,
            @Param("running") AgentRunStatus running);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AgentRun r set r.status = :cancelled, r.completedAt = :now, "
            + "r.outputText = :output, r.errorCode = null, "
            + "r.pendingToolCallId = null, r.pendingToolName = null, "
            + "r.pendingArgumentsJson = null, r.pendingPreviewJson = null, "
            + "r.version = r.version + 1 where r.id = :runId and r.operatorId = :operatorId "
            + "and (r.status = :running or r.status = :waiting)")
    int cancelActive(
            @Param("runId") Long runId,
            @Param("operatorId") Long operatorId,
            @Param("running") AgentRunStatus running,
            @Param("waiting") AgentRunStatus waiting,
            @Param("cancelled") AgentRunStatus cancelled,
            @Param("now") LocalDateTime now,
            @Param("output") String output);
}
