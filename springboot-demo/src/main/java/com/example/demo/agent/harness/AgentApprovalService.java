package com.example.demo.agent.harness;

import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.model.AgentToolCall;
import com.example.demo.agent.policy.AgentPolicyDecision;
import com.example.demo.agent.tool.AgentPreparedTool;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class AgentApprovalService {
    private final AgentRunStore runStore;
    private final AgentToolExecutor toolExecutor;
    private final AgentContextManager contextManager;
    private final AgentRunResultFactory resultFactory;

    public AgentApprovalService(
            AgentRunStore runStore,
            AgentToolExecutor toolExecutor,
            AgentContextManager contextManager,
            AgentRunResultFactory resultFactory) {
        this.runStore = runStore;
        this.toolExecutor = toolExecutor;
        this.contextManager = contextManager;
        this.resultFactory = resultFactory;
    }

    public AgentRunResult pause(
            AgentRun run,
            AgentRunContext context,
            AgentPreparedTool prepared) {
        AgentToolCall call = prepared.hookContext().call();
        AgentTool tool = prepared.tool();
        Object preview = prepared.approvalPreview();
        run.setStatus(AgentRunStatus.WAITING_APPROVAL);
        run.setPendingToolCallId(call.getId());
        run.setPendingToolName(call.getName());
        run.setPendingArgumentsJson(contextManager.toJson(call.getArguments()));
        run.setPendingPreviewJson(contextManager.toJson(preview));
        run.setContextJson(contextManager.toJson(context.getToolResults()));
        run.setOutputText("即将执行“" + tool.definition().getDescription()
                + "”，请确认。影响预览：" + contextManager.toJson(preview));
        AgentRun saved = runStore.save(run);
        return resultFactory.create(saved, resultFactory.pendingAction(saved, tool, preview));
    }

    public AgentApprovalClaim claim(Long runId, Long operatorId) {
        LocalDateTime now = LocalDateTime.now();
        if (runStore.claimApproval(runId, operatorId, now)) {
            return new AgentApprovalClaim(runStore.requireClaimed(runId), null);
        }
        AgentRun existing = runStore.require(runId, "Agent 任务不存在");
        if (!operatorId.equals(existing.getOperatorId())) {
            throw new IllegalArgumentException("无权处理其他操作人的 Agent 任务");
        }
        if (existing.getStatus() == AgentRunStatus.WAITING_APPROVAL
                && (existing.getExpiresAt() == null || !existing.getExpiresAt().isAfter(now))) {
            existing.setStatus(AgentRunStatus.EXPIRED);
            existing.setCompletedAt(now);
            existing.setOutputText("确认已超时，请重新发起任务");
            return new AgentApprovalClaim(null, resultFactory.create(runStore.save(existing)));
        }
        throw new IllegalArgumentException("Agent 任务已被处理或当前不等待确认");
    }

    public AgentRunResult cancel(AgentRun run) {
        run.setStatus(AgentRunStatus.CANCELLED);
        run.setCompletedAt(LocalDateTime.now());
        run.setOutputText("用户已取消本次操作");
        clearPending(run);
        return resultFactory.create(runStore.save(run));
    }

    public AgentApprovedTool prepareApproved(AgentRun run, Long operatorId) {
        Map<String, Object> arguments = contextManager.readArguments(run.getPendingArgumentsJson());
        AgentToolCall call = new AgentToolCall(
                run.getPendingToolCallId(), run.getPendingToolName(), arguments);
        AgentPreparedTool prepared = toolExecutor.prepareApproved(run.getId(), operatorId, call);
        if (prepared.decision() != AgentPolicyDecision.REQUIRE_APPROVAL) {
            throw new IllegalStateException("待确认工具的风险级别已变化，请重新发起任务");
        }
        return new AgentApprovedTool(call, arguments, prepared);
    }

    public AgentRun saveSuccessfulExecution(
            AgentRun run,
            AgentRunContext context,
            Object toolResult) {
        run.setStatus(AgentRunStatus.RUNNING);
        run.setOutputText(contextManager.toJson(toolResult));
        run.setContextJson(contextManager.toJson(context.getToolResults()));
        clearPending(run);
        return runStore.save(run);
    }

    public AgentRunResult completeWithoutGateway(AgentRun run, Object toolResult) {
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setCompletedAt(LocalDateTime.now());
        run.setOutputText("操作已成功执行。结果：" + contextManager.toJson(toolResult));
        return resultFactory.create(runStore.save(run));
    }

    public AgentRunResult fail(AgentRun run, boolean writeSucceeded, RuntimeException exception) {
        run.setStatus(writeSucceeded ? AgentRunStatus.COMPLETED : AgentRunStatus.FAILED);
        run.setErrorCode(exception.getClass().getSimpleName());
        run.setOutputText(writeSucceeded
                ? "操作已成功执行，但后续处理失败：" + exception.getMessage()
                : exception.getMessage());
        run.setCompletedAt(LocalDateTime.now());
        clearPending(run);
        return resultFactory.create(runStore.save(run));
    }

    private void clearPending(AgentRun run) {
        run.setPendingToolCallId(null);
        run.setPendingToolName(null);
        run.setPendingArgumentsJson(null);
        run.setPendingPreviewJson(null);
    }
}
