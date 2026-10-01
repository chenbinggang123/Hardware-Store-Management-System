package com.example.demo.agent.harness;

import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.repository.AgentRunRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class AgentRunStore {
    private final AgentRunRepository repository;

    public AgentRunStore(AgentRunRepository repository) {
        this.repository = repository;
    }

    public AgentRun create(Long operatorId, String inputText) {
        LocalDateTime now = LocalDateTime.now();
        AgentRun run = new AgentRun();
        run.setOperatorId(operatorId);
        run.setStatus(AgentRunStatus.RUNNING);
        run.setCurrentStep(0);
        run.setInputText(inputText.trim());
        run.setStartedAt(now);
        run.setExpiresAt(now.plusMinutes(30));
        return repository.save(run);
    }

    public AgentRun save(AgentRun run) {
        return repository.save(run);
    }

    public AgentRun require(Long runId, String notFoundMessage) {
        return repository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException(notFoundMessage));
    }

    public AgentRun requireClaimed(Long runId) {
        return repository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("已抢占的 Agent 任务不存在"));
    }

    public boolean claimApproval(Long runId, Long operatorId, LocalDateTime now) {
        return repository.claimApproval(
                runId,
                operatorId,
                AgentRunStatus.WAITING_APPROVAL,
                AgentRunStatus.RUNNING,
                now) == 1;
    }
}
