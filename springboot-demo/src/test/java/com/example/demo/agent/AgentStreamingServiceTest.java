package com.example.demo.agent;

import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.harness.AgentHarness;
import com.example.demo.agent.harness.AgentPreparedRun;
import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.harness.AgentRunResult;
import com.example.demo.agent.harness.AgentRunStatus;
import com.example.demo.agent.harness.AgentRunStore;
import com.example.demo.agent.model.AgentModelGateway;
import com.example.demo.agent.service.AgentStreamingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentStreamingServiceTest {
    @Test
    void returnsRunImmediatelyAndPersistsRealModelDeltas() {
        AgentHarness harness = mock(AgentHarness.class);
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentModelGateway gateway = mock(AgentModelGateway.class);
        AgentRun run = new AgentRun();
        run.setId(31L);
        run.setStatus(AgentRunStatus.RUNNING);
        AgentRunResult initial = new AgentRunResult(31L, AgentRunStatus.RUNNING, null);
        AgentPreparedRun prepared = new AgentPreparedRun(
                run, new AgentRunContext(31L, 7L, "查库存", List.of()), initial);
        when(harness.prepareRun("查库存", "查库存", 7L, 5L)).thenReturn(prepared);
        doAnswer(invocation -> {
            com.example.demo.agent.model.AgentModelStreamListener listener = invocation.getArgument(2);
            listener.onDelta("库存");
            listener.onDelta(" 24 件");
            return new AgentRunResult(31L, AgentRunStatus.COMPLETED, "库存 24 件");
        }).when(harness).continuePrepared(eq(prepared), eq(gateway), any(), eq(false));

        AgentStreamingService service = new AgentStreamingService(harness, runStore, Runnable::run);
        AgentRunResult result = service.start("查库存", "查库存", 7L, gateway, 5L);

        assertEquals(31L, result.getRunId());
        assertEquals(AgentRunStatus.RUNNING, result.getStatus());
        verify(runStore).updateRunningOutput(31L, "库存");
    }

    @Test
    void cancellationPersistsStatusAndInterruptsQueuedWork() {
        AgentHarness harness = mock(AgentHarness.class);
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentModelGateway gateway = mock(AgentModelGateway.class);
        AgentRun run = new AgentRun();
        run.setId(42L);
        run.setStatus(AgentRunStatus.RUNNING);
        AgentRunResult initial = new AgentRunResult(42L, AgentRunStatus.RUNNING, null);
        AgentPreparedRun prepared = new AgentPreparedRun(
                run, new AgentRunContext(42L, 7L, "生成销售草稿", List.of()), initial);
        when(harness.prepareRun("生成销售草稿", "生成销售草稿", 7L, 5L)).thenReturn(prepared);
        AgentRunResult cancelled = new AgentRunResult(42L, AgentRunStatus.CANCELLED, "用户已取消本次任务");
        when(harness.getRun(42L, 7L)).thenReturn(cancelled);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AgentStreamingService service = new AgentStreamingService(harness, runStore, queued::set);
        service.start("生成销售草稿", "生成销售草稿", 7L, gateway, 5L);

        AgentRunResult result = service.cancel(42L, 7L);

        assertEquals(AgentRunStatus.CANCELLED, result.getStatus());
        assertTrue(((Future<?>) queued.get()).isCancelled());
        verify(runStore).cancelActive(42L, 7L);
    }
}
