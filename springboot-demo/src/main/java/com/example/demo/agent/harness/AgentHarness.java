package com.example.demo.agent.harness;

import com.example.demo.agent.dto.AgentFileView;
import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.hook.AgentHookChain;
import com.example.demo.agent.hook.TraceAgentLifecycleHook;
import com.example.demo.agent.model.AgentModelGateway;
import com.example.demo.agent.model.AgentModelResponse;
import com.example.demo.agent.model.AgentModelStreamListener;
import com.example.demo.agent.model.AgentToolCall;
import com.example.demo.agent.model.AgentChatMessage;
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
import java.util.HashSet;
import com.fasterxml.jackson.core.JsonProcessingException;

@Component
public class AgentHarness {
    private static final int MAX_MODEL_STEPS = 6;
    private static final int MAX_TOOL_CALLS = 10;

    private final AgentRunStore runStore;
    private final AgentToolExecutor toolExecutor;
    private final AgentContextManager contextManager;
    private final AgentApprovalService approvalService;
    private final AgentRunResultFactory resultFactory;
    private final AgentRoutingService routingService;
    private final ObjectMapper planMapper;

    @Autowired
    public AgentHarness(
            AgentRunStore runStore,
            AgentToolExecutor toolExecutor,
            AgentContextManager contextManager,
            AgentApprovalService approvalService,
            AgentRunResultFactory resultFactory,
            AgentRoutingService routingService,
            ObjectMapper objectMapper) {
        this.runStore = runStore;
        this.toolExecutor = toolExecutor;
        this.contextManager = contextManager;
        this.approvalService = approvalService;
        this.resultFactory = resultFactory;
        this.routingService = routingService;
        this.planMapper = objectMapper;
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
        this.routingService = new AgentRoutingService(new AgentCapabilityRegistry(), new AgentInstructionLoader(), false);
        this.planMapper = objectMapper;
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
        AgentPreparedRun prepared = prepareRun(userInput, modelInput, operatorId, conversationId);
        return continuePrepared(prepared, gateway, AgentModelStreamListener.NONE, false);
    }

    public AgentPreparedRun prepareRun(
            String userInput,
            String modelInput,
            Long operatorId,
            Long conversationId) {
        if (!StringUtils.hasText(userInput)) {
            throw new IllegalArgumentException("Agent 输入不能为空");
        }
        if (!StringUtils.hasText(modelInput)) {
            throw new IllegalArgumentException("Agent 模型输入不能为空");
        }
        AgentStartContext startContext = contextManager.prepareStart(conversationId, operatorId, userInput);
        AgentRun run = runStore.create(operatorId, userInput);
        AgentRunContext context = contextManager.start(run, modelInput, startContext);
        return new AgentPreparedRun(run, context, resultFactory.create(run));
    }

    public AgentRunResult continuePrepared(
            AgentPreparedRun prepared,
            AgentModelGateway gateway,
            AgentModelStreamListener streamListener,
            boolean writeAlreadySucceeded) {
        if (gateway == null) {
            throw new IllegalArgumentException("Agent 模型网关不能为空");
        }
        return continueRun(
                prepared.run(),
                prepared.context(),
                gateway,
                streamListener == null ? AgentModelStreamListener.NONE : streamListener,
                writeAlreadySucceeded);
    }

    private AgentRunResult continueRun(
            AgentRun run,
            AgentRunContext context,
            AgentModelGateway gateway,
            AgentModelStreamListener streamListener,
            boolean writeAlreadySucceeded) {
        Set<String> executedCalls = contextManager.fingerprints(context);
        int totalToolCalls = context.getToolResults().size();
        try {
            PlanLoadResult planLoad = loadOrCreateExecutionPlan(run, context, gateway);
            run = planLoad.run();
            AgentExecutionPlan executionPlan = planLoad.plan();
            context.setExecutionPlan(new HashSet<>(executionPlan.allowedTools()), executionPlan.instructionSnapshot(), !executionPlan.selections().isEmpty());
            if (!"READY".equals(executionPlan.status())) {
                run.setStatus(AgentRunStatus.COMPLETED);
                run.setOutputText(StringUtils.hasText(executionPlan.message()) ? executionPlan.message()
                        : "当前请求暂时无法处理，请补充说明或换一种表达。");
                run.setCompletedAt(LocalDateTime.now());
                return resultFactory.create(runStore.save(run));
            }
            List<com.example.demo.agent.tool.AgentToolDefinition> allowedDefinitions = toolExecutor.definitions().stream()
                    .filter(definition -> executionPlan.allowedTools().contains(definition.getName())).toList();
            int firstStep = Math.max(run.getCurrentStep() == null ? 0 : run.getCurrentStep(), 0) + 1;
            for (int step = firstStep; step <= MAX_MODEL_STEPS; step++) {
                AgentRunResult cancelled = cancelledResult(run);
                if (cancelled != null) return cancelled;
                run.setCurrentStep(step);
                run = runStore.save(run);
                AgentModelResponse response = streamListener == AgentModelStreamListener.NONE
                        ? gateway.respond(context, allowedDefinitions)
                        : gateway.respondStreaming(context, allowedDefinitions, streamListener);
                if (response == null) {
                    throw new IllegalStateException("模型返回为空");
                }
                cancelled = cancelledResult(run);
                if (cancelled != null) return cancelled;
                if (response.hasFinalOutput()) {
                    if ((workbookWasExecuted(context) && !hasReadyWorkbookOutput(run.getId()))
                            || (isWorkbookCreationRequest(context.getUserInput())
                            && claimsWorkbookSuccess(response.getFinalOutput())
                            && !hasReadyWorkbookOutput(run.getId()))) {
                        return failUnverifiedWorkbook(run);
                    }
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
                    cancelled = cancelledResult(run);
                    if (cancelled != null) return cancelled;
                    totalToolCalls++;
                    if (totalToolCalls > MAX_TOOL_CALLS) {
                        throw new IllegalStateException("Agent 工具调用次数超过限制");
                    }
                    String fingerprint = call.getName() + ":" + contextManager.toJson(call.getArguments());
                    if (!executedCalls.add(fingerprint)) {
                        throw new IllegalStateException("检测到重复工具调用：" + call.getName());
                    }
                    AgentPreparedTool prepared = toolExecutor.prepare(
                            run.getId(), context.getOperatorId(), call, Set.copyOf(executionPlan.allowedTools()));
                    if (prepared.decision() == AgentPolicyDecision.REQUIRE_APPROVAL) {
                        return approvalService.pause(run, context, prepared);
                    }
                    Object toolResult = toolExecutor.execute(prepared);
                    context.addToolResult(call.getId(), call.getName(), call.getArguments(), toolResult);
                    if (prepared.tool().completesRun()) {
                        run.setContextJson(contextManager.toJson(context.getToolResults()));
                        return approvalService.completeWithoutGateway(run, toolResult);
                    }
                }
            }
            throw new IllegalStateException("Agent 模型调用次数超过限制");
        } catch (RuntimeException exception) {
            AgentRunResult cancelled = cancelledResult(run);
            if (cancelled != null) return cancelled;
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

    private PlanLoadResult loadOrCreateExecutionPlan(
            AgentRun run, AgentRunContext context, AgentModelGateway gateway) {
        if (StringUtils.hasText(run.getExecutionPlanJson())) {
            try {
                return new PlanLoadResult(run,
                        planMapper.readValue(run.getExecutionPlanJson(), AgentExecutionPlan.class));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Agent 执行计划损坏", exception);
            }
        }
        AgentExecutionPlan plan = routingService.plan(run.getInputText(), context.getUserInput(),
                context.getConversationHistory(), gateway, toolExecutor.definitions());
        try {
            run.setExecutionPlanJson(planMapper.writeValueAsString(plan));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 执行计划无法保存", exception);
        }
        return new PlanLoadResult(runStore.save(run), plan);
    }

    private record PlanLoadResult(AgentRun run, AgentExecutionPlan plan) { }

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
            if ("generate_excel_workbook".equals(approvedTool.call().getName())
                    && !hasReadyWorkbookOutput(run.getId(), result)) {
                return approvalService.fail(run, false,
                        new IllegalStateException("Excel 文件未通过保存校验，不能确认生成成功"));
            }
            writeSucceeded = true;
            AgentRunContext runContext = contextManager.restore(run);
            AgentToolCall call = approvedTool.call();
            runContext.addToolResult(call.getId(), call.getName(), approvedTool.arguments(), result);
            run = approvalService.saveSuccessfulExecution(run, runContext, result);
            if (gateway == null || "prepare_billing_document".equals(call.getName())) {
                if (workbookWasExecuted(runContext) && !hasReadyWorkbookOutput(run.getId())) {
                    return failUnverifiedWorkbook(run);
                }
                return approvalService.completeWithoutGateway(run, result);
            }
            return continueRun(run, runContext, gateway, AgentModelStreamListener.NONE, true);
        } catch (RuntimeException exception) {
            return approvalService.fail(run, writeSucceeded, exception);
        }
    }

    private AgentRunResult cancelledResult(AgentRun run) {
        if (!Thread.currentThread().isInterrupted()) return null;
        return currentCancelledResult(run);
    }

    private AgentRunResult currentCancelledResult(AgentRun run) {
        AgentRun current = runStore.require(run.getId(), "Agent 任务不存在");
        return current.getStatus() == AgentRunStatus.CANCELLED
                ? resultFactory.create(current)
                : null;
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

    private boolean workbookWasExecuted(AgentRunContext context) {
        return context.getToolResults().stream()
                .anyMatch(item -> "generate_excel_workbook".equals(item.get("toolName")));
    }

    private boolean hasReadyWorkbookOutput(Long runId) {
        return resultFactory.filesForRun(runId).stream()
                .anyMatch(file -> runId.equals(file.runId()) && "READY".equals(file.status()));
    }

    private boolean hasReadyWorkbookOutput(Long runId, Object result) {
        if (!(result instanceof java.util.Map<?, ?> resultMap)
                || !(resultMap.get("outputId") instanceof Number outputId)) return false;
        return resultFactory.filesForRun(runId).stream()
                .anyMatch(file -> file.id() != null && outputId.longValue() == file.id()
                        && runId.equals(file.runId()) && "READY".equals(file.status()));
    }

    private boolean isWorkbookCreationRequest(String input) {
        if (!StringUtils.hasText(input)) return false;
        String text = input.toLowerCase();
        boolean mentionsWorkbook = text.contains("excel") || text.contains(".xlsx")
                || text.contains("表格") || text.contains("工作簿");
        boolean asksToCreate = List.of("生成", "制作", "导出", "创建", "做一份", "做成")
                .stream().anyMatch(text::contains);
        boolean asksAboutExisting = List.of("在哪", "位置", "找回", "查找", "是否生成", "有没有生成",
                "生成过", "之前生成", "查一下").stream().anyMatch(text::contains);
        return mentionsWorkbook && asksToCreate && !asksAboutExisting;
    }

    private boolean claimsWorkbookSuccess(String output) {
        if (!StringUtils.hasText(output)) return false;
        String text = output.toLowerCase();
        if (List.of("未生成", "没有生成", "无法生成", "未能生成", "不能确认生成")
                .stream().anyMatch(text::contains)) return false;
        boolean mentionsWorkbook = text.contains("excel") || text.contains(".xlsx")
                || text.contains("表格") || text.contains("文件");
        boolean claimsSuccess = List.of("文件已生成", "已生成文件", "已生成了", "生成成功", "已成功生成",
                "已经生成").stream().anyMatch(text::contains);
        return mentionsWorkbook && claimsSuccess;
    }

    private AgentRunResult failUnverifiedWorkbook(AgentRun run) {
        run.setStatus(AgentRunStatus.FAILED);
        run.setErrorCode("WORKBOOK_OUTPUT_NOT_VERIFIED");
        run.setOutputText("我没有查到本次任务对应的已保存 Excel 文件，因此不能确认生成成功。请检查文件中心后再重试。");
        run.setCompletedAt(LocalDateTime.now());
        return resultFactory.create(runStore.save(run));
    }
}
