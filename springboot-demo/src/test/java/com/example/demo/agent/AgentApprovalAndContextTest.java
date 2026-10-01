package com.example.demo.agent;

import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.harness.AgentApprovalClaim;
import com.example.demo.agent.harness.AgentApprovalService;
import com.example.demo.agent.harness.AgentContextManager;
import com.example.demo.agent.harness.AgentHarness;
import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.harness.AgentRunResult;
import com.example.demo.agent.harness.AgentRunResultFactory;
import com.example.demo.agent.harness.AgentRunStatus;
import com.example.demo.agent.harness.AgentRunStore;
import com.example.demo.agent.tool.AgentToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentApprovalAndContextTest {

    @Test
    void expiresApprovalThatCanNoLongerBeClaimed() {
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentRun run = waitingRun();
        run.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(runStore.claimApproval(any(), any(), any())).thenReturn(false);
        when(runStore.require(31L, "Agent 任务不存在")).thenReturn(run);
        when(runStore.save(any(AgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AgentApprovalService service = service(runStore);

        AgentApprovalClaim claim = service.claim(31L, 7L);

        assertTrue(claim.isTerminal());
        assertEquals(AgentRunStatus.EXPIRED, claim.terminalResult().getStatus());
        assertEquals("确认已超时，请重新发起任务", claim.terminalResult().getOutput());
    }

    @Test
    void cancellingApprovalClearsPendingOperation() {
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentRun run = waitingRun();
        when(runStore.save(any(AgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AgentApprovalService service = service(runStore);

        AgentRunResult result = service.cancel(run);

        assertEquals(AgentRunStatus.CANCELLED, result.getStatus());
        assertNull(run.getPendingToolCallId());
        assertNull(run.getPendingToolName());
        assertNull(run.getPendingArgumentsJson());
        assertNull(run.getPendingPreviewJson());
        verify(runStore).save(run);
    }

    @Test
    void restoresPersistedToolResultsIntoModelContext() {
        AgentContextManager contextManager = new AgentContextManager(new ObjectMapper(), null);
        AgentRun run = new AgentRun();
        run.setId(42L);
        run.setOperatorId(7L);
        run.setInputText("查询电钻");
        run.setContextJson("[{\"toolCallId\":\"c1\",\"toolName\":\"search_products\","
                + "\"arguments\":{\"keyword\":\"电钻\"},\"result\":{\"count\":1}}]");

        AgentRunContext context = contextManager.restore(run);

        assertEquals(1, context.getToolResults().size());
        assertEquals("search_products", context.getToolResults().get(0).get("toolName"));
        assertTrue(contextManager.fingerprints(context).iterator().next().contains("电钻"));
    }

    @Test
    void approvalPreparationFailureFinishesClaimedRunInsteadOfLeavingItRunning() {
        AgentRunStore runStore = mock(AgentRunStore.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        AgentContextManager contextManager = mock(AgentContextManager.class);
        AgentApprovalService approvalService = mock(AgentApprovalService.class);
        AgentRunResultFactory resultFactory = mock(AgentRunResultFactory.class);
        AgentRun run = waitingRun();
        run.setStatus(AgentRunStatus.RUNNING);
        IllegalStateException failure = new IllegalStateException("待确认工具的风险级别已变化，请重新发起任务");
        AgentRunResult failedResult = new AgentRunResult(
                run.getId(), AgentRunStatus.FAILED, failure.getMessage());
        when(approvalService.claim(31L, 7L)).thenReturn(new AgentApprovalClaim(run, null));
        when(approvalService.prepareApproved(run, 7L)).thenThrow(failure);
        when(approvalService.fail(run, false, failure)).thenReturn(failedResult);
        AgentHarness harness = new AgentHarness(
                runStore, toolExecutor, contextManager, approvalService, resultFactory);

        AgentRunResult result = harness.resolveApproval(31L, 7L, true, null);

        assertSame(failedResult, result);
        verify(approvalService).fail(run, false, failure);
    }

    private AgentApprovalService service(AgentRunStore runStore) {
        AgentContextManager contextManager = new AgentContextManager(new ObjectMapper(), null);
        return new AgentApprovalService(
                runStore,
                mock(AgentToolExecutor.class),
                contextManager,
                new AgentRunResultFactory(null));
    }

    private AgentRun waitingRun() {
        AgentRun run = new AgentRun();
        run.setId(31L);
        run.setOperatorId(7L);
        run.setStatus(AgentRunStatus.WAITING_APPROVAL);
        run.setPendingToolCallId("call-31");
        run.setPendingToolName("create_product");
        run.setPendingArgumentsJson("{\"name\":\"电钻\"}");
        run.setPendingPreviewJson("{\"name\":\"电钻\"}");
        return run;
    }
}
