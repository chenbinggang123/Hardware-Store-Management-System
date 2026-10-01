package com.example.demo.agent.harness;

import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.hook.AgentHookChain;
import com.example.demo.agent.hook.TraceAgentLifecycleHook;
import com.example.demo.agent.model.AgentModelGateway;
import com.example.demo.agent.model.AgentModelResponse;
import com.example.demo.agent.model.AgentToolCall;
import com.example.demo.agent.policy.AgentPolicyDecision;
import com.example.demo.agent.policy.AgentPolicyEngine;
import com.example.demo.agent.repository.AgentRunRepository;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.tool.AgentPreparedTool;
import com.example.demo.agent.tool.AgentToolExecutor;
import com.example.demo.agent.tool.AgentToolRegistry;
import com.example.demo.agent.trace.AgentTraceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Component
public class AgentHarness {
    private static final int MAX_MODEL_STEPS = 6;
    private static final int MAX_TOOL_CALLS = 10;

    private final AgentRunStore runStore;
    private final AgentToolExecutor toolExecutor;
    private final AgentContextManager contextManager;
    private final AgentApprovalService approvalService;
    private final AgentRunResultFactory resultFactory;

    @Autowired
    public AgentHarness(
            AgentRunStore runStore,
            AgentToolExecutor toolExecutor,
            AgentContextManager contextManager,
            AgentApprovalService approvalService,
            AgentRunResultFactory resultFactory) {
        this.runStore = runStore;
        this.toolExecutor = toolExecutor;
        this.contextManager = contextManager;
        this.approvalService = approvalService;
        this.resultFactory = resultFactory;
    }

    public AgentHarness(
            AgentRunRepository runRepository,
            AgentToolRegistry toolRegistry,
            AgentPolicyEngine policyEngine,
            AgentTraceRecorder traceRecorder,
            ObjectMapper objectMapper,
            AgentConversationService conversationService) {
        this.runStore = new AgentRunStore(runRepository);
        this.toolExecutor = new AgentToolExecutor(
                toolRegistry,
                policyEngine,
                new AgentHookChain(List.of(new TraceAgentLifecycleHook(traceRecorder))));
        this.contextManager = new AgentContextManager(objectMapper, conversationService);
        this.resultFactory = new AgentRunResultFactory(conversationService);
        this.approvalService = new AgentApprovalService(
                runStore, toolExecutor, contextManager, resultFactory);
    }

    public AgentHarness(
            AgentRunRepository runRepository,
            AgentToolRegistry toolRegistry,
            AgentPolicyEngine policyEngine,
            AgentTraceRecorder traceRecorder,
            ObjectMapper objectMapper) {
        this(runRepository, toolRegistry, policyEngine, traceRecorder, objectMapper, null);
    }

    public AgentRunResult run(String userInput, Long operatorId, AgentModelGateway gateway) {
        return run(userInput, operatorId, gateway, null);
    }

    public AgentRunResult run(
            String userInput,
            Long operatorId,
            AgentModelGateway gateway,
            Long conversationId) {
        return run(userInput, userInput, operatorId, gateway, conversationId);
    }

    public AgentRunResult run(
            String userInput,
            String modelInput,
            Long operatorId,
            AgentModelGateway gateway,
            Long conversationId) {
        if (!StringUtils.hasText(userInput)) {
            throw new IllegalArgumentException("Agent 输入不能为空");
        }
        if (!StringUtils.hasText(modelInput)) {
            throw new IllegalArgumentException("Agent 模型输入不能为空");
        }
        if (gateway == null) {
            throw new IllegalArgumentException("Agent 模型网关不能为空");
        }

        AgentStartContext startContext = contextManager.prepareStart(conversationId, operatorId, userInput);
        AgentRun run = runStore.create(operatorId, userInput);
        AgentRunContext context = contextManager.start(run, modelInput, startContext);
        return continueRun(run, context, gateway, false);
    }

    private AgentRunResult continueRun(
            AgentRun run,
            AgentRunContext context,
            AgentModelGateway gateway,
            boolean writeAlreadySucceeded) {
        Set<String> executedCalls = contextManager.fingerprints(context);
        int totalToolCalls = context.getToolResults().size();
        try {
            int firstStep = Math.max(run.getCurrentStep() == null ? 0 : run.getCurrentStep(), 0) + 1;
            for (int step = firstStep; step <= MAX_MODEL_STEPS; step++) {
                run.setCurrentStep(step);
                run = runStore.save(run);
                AgentModelResponse response = gateway.respond(context, toolExecutor.definitions());
                if (response == null) {
                    throw new IllegalStateException("模型返回为空");
                }
                if (response.hasFinalOutput()) {
                    run.setStatus(AgentRunStatus.COMPLETED);
                    run.setOutputText(response.getFinalOutput());
                    run.setCompletedAt(LocalDateTime.now());
                    run = runStore.save(run);
                    return resultFactory.create(run);
                }
                if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
                    throw new IllegalStateException("模型既未返回结果，也未请求工具");
                }
                for (AgentToolCall call : response.getToolCalls()) {
                    totalToolCalls++;
                    if (totalToolCalls > MAX_TOOL_CALLS) {
                        throw new IllegalStateException("Agent 工具调用次数超过限制");
                    }
                    String fingerprint = call.getName() + ":" + contextManager.toJson(call.getArguments());
                    if (!executedCalls.add(fingerprint)) {
                        throw new IllegalStateException("检测到重复工具调用：" + call.getName());
                    }
                    AgentPreparedTool prepared = toolExecutor.prepare(
                            run.getId(), context.getOperatorId(), call);
                    if (prepared.decision() == AgentPolicyDecision.REQUIRE_APPROVAL) {
                        return approvalService.pause(run, context, prepared);
                    }
                    Object toolResult = toolExecutor.execute(prepared);
                    context.addToolResult(call.getId(), call.getName(), call.getArguments(), toolResult);
                }
            }
            throw new IllegalStateException("Agent 模型调用次数超过限制");
        } catch (RuntimeException exception) {
            run.setStatus(writeAlreadySucceeded ? AgentRunStatus.COMPLETED : AgentRunStatus.FAILED);
            run.setErrorCode(exception.getClass().getSimpleName());
            run.setOutputText(writeAlreadySucceeded
                    ? "操作已成功执行，但生成结果说明失败：" + exception.getMessage()
                    : exception.getMessage());
            run.setCompletedAt(LocalDateTime.now());
            run = runStore.save(run);
            return resultFactory.create(run);
        }
    }

    public AgentRunResult resolveApproval(
            Long runId,
            Long operatorId,
            boolean approved,
            AgentModelGateway gateway) {
        AgentApprovalClaim claim = approvalService.claim(runId, operatorId);
        if (claim.isTerminal()) {
            return claim.terminalResult();
        }
        AgentRun run = claim.run();
        if (!approved) {
            return approvalService.cancel(run);
        }

        boolean writeSucceeded = false;
        try {
            AgentApprovedTool approvedTool = approvalService.prepareApproved(run, operatorId);
            Object result = toolExecutor.execute(approvedTool.prepared());
            writeSucceeded = true;
            AgentRunContext runContext = contextManager.restore(run);
            AgentToolCall call = approvedTool.call();
            runContext.addToolResult(call.getId(), call.getName(), approvedTool.arguments(), result);
            run = approvalService.saveSuccessfulExecution(run, runContext, result);
            if (gateway == null) {
                return approvalService.completeWithoutGateway(run, result);
            }
            return continueRun(run, runContext, gateway, true);
        } catch (RuntimeException exception) {
            return approvalService.fail(run, writeSucceeded, exception);
        }
    }

    public AgentRunResult getRun(Long runId, Long operatorId) {
        AgentRun run = runStore.require(runId, "Agent 任务不存在");
        if (!operatorId.equals(run.getOperatorId())) {
            throw new IllegalArgumentException("无权查看其他操作人的 Agent 任务");
        }
        if (run.getStatus() != AgentRunStatus.WAITING_APPROVAL) {
            return resultFactory.create(run);
        }
        return resultFactory.create(
                run,
                resultFactory.pendingAction(
                        run,
                        toolExecutor.require(run.getPendingToolName()),
                        contextManager.readValue(run.getPendingPreviewJson())));
    }
}
