package com.example.demo.agent.service;

import com.example.demo.agent.harness.AgentHarness;
import com.example.demo.agent.harness.AgentPreparedRun;
import com.example.demo.agent.harness.AgentRunResult;
import com.example.demo.agent.harness.AgentRunStatus;
import com.example.demo.agent.harness.AgentRunStore;
import com.example.demo.agent.model.AgentModelGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AgentStreamingService {
    private static final long PERSIST_INTERVAL_MS = 120;

    private final AgentHarness harness;
    private final AgentRunStore runStore;
    private final Executor executor;
    private final Map<Long, Future<?>> activeRuns = new ConcurrentHashMap<>();

    public AgentStreamingService(
            AgentHarness harness,
            AgentRunStore runStore,
            @Qualifier("agentTaskExecutor") Executor executor) {
        this.harness = harness;
        this.runStore = runStore;
        this.executor = executor;
    }

    public AgentRunResult start(
            String displayInput,
            String modelInput,
            Long operatorId,
            AgentModelGateway gateway,
            Long conversationId) {
        AgentPreparedRun prepared = harness.prepareRun(displayInput, modelInput, operatorId, conversationId);
        Long runId = prepared.run().getId();
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                execute(prepared, gateway);
            } finally {
                activeRuns.remove(runId);
            }
            return null;
        });
        activeRuns.put(runId, task);
        try {
            executor.execute(task);
        } catch (RuntimeException exception) {
            activeRuns.remove(runId, task);
            prepared.run().setStatus(AgentRunStatus.FAILED);
            prepared.run().setErrorCode(exception.getClass().getSimpleName());
            prepared.run().setOutputText("Agent 任务队列暂时不可用，请稍后重试");
            prepared.run().setCompletedAt(LocalDateTime.now());
            runStore.save(prepared.run());
        }
        return prepared.initialResult();
    }

    public AgentRunResult cancel(Long runId, Long operatorId) {
        runStore.cancelActive(runId, operatorId);
        Future<?> task = activeRuns.remove(runId);
        if (task != null) task.cancel(true);
        return harness.getRun(runId, operatorId);
    }

    private void execute(AgentPreparedRun prepared, AgentModelGateway gateway) {
        StringBuilder streamedOutput = new StringBuilder();
        AtomicLong lastPersistedAt = new AtomicLong(0);
        harness.continuePrepared(prepared, gateway, delta -> {
            if (delta == null || delta.isEmpty()) return;
            synchronized (streamedOutput) {
                streamedOutput.append(delta);
                long now = System.currentTimeMillis();
                if (lastPersistedAt.get() == 0 || now - lastPersistedAt.get() >= PERSIST_INTERVAL_MS) {
                    runStore.updateRunningOutput(prepared.run().getId(), streamedOutput.toString());
                    lastPersistedAt.set(now);
                }
            }
        }, false);
    }
}
