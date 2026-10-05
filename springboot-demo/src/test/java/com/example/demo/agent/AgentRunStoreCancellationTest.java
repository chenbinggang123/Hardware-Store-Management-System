package com.example.demo.agent;

import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.harness.AgentRunStatus;
import com.example.demo.agent.harness.AgentRunStore;
import com.example.demo.agent.repository.AgentRunRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunStoreCancellationTest {
    @Test
    void atomicallyCancelsAnOwnedRunningRun() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AgentRun running = run(21L, 7L, AgentRunStatus.RUNNING);
        AgentRun cancelled = run(21L, 7L, AgentRunStatus.CANCELLED);
        when(repository.findById(21L))
                .thenReturn(Optional.of(running))
                .thenReturn(Optional.of(cancelled));
        when(repository.cancelActive(
                eq(21L), eq(7L), eq(AgentRunStatus.RUNNING), eq(AgentRunStatus.WAITING_APPROVAL),
                eq(AgentRunStatus.CANCELLED), any(), eq("用户已取消本次任务")))
                .thenReturn(1);

        AgentRun result = new AgentRunStore(repository).cancelActive(21L, 7L);

        assertEquals(AgentRunStatus.CANCELLED, result.getStatus());
    }

    @Test
    void rejectsCancellationByAnotherOperator() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        when(repository.findById(21L)).thenReturn(Optional.of(run(21L, 8L, AgentRunStatus.RUNNING)));

        assertThrows(IllegalArgumentException.class,
                () -> new AgentRunStore(repository).cancelActive(21L, 7L));
        verify(repository, never()).cancelActive(any(), any(), any(), any(), any(), any(), any());
    }

    private AgentRun run(Long id, Long operatorId, AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setOperatorId(operatorId);
        run.setStatus(status);
        return run;
    }
}
