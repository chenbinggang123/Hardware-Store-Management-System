package com.example.demo.agent.harness;

import com.example.demo.agent.entity.AgentConversation;
import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.model.AgentModelGateway;
import com.example.demo.agent.model.AgentModelResponse;
import com.example.demo.agent.model.AgentToolCall;
import com.example.demo.agent.policy.AgentPolicyDecision;
import com.example.demo.agent.policy.AgentPolicyEngine;
import com.example.demo.agent.repository.AgentRunRepository;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolRegistry;
import com.example.demo.agent.trace.AgentTraceRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AgentHarness {
    private static final int MAX_MODEL_STEPS = 6;
    private static final int MAX_TOOL_CALLS = 10;

    private final AgentRunRepository runRepository;
    private final AgentToolRegistry toolRegistry;
    private final AgentPolicyEngine policyEngine;
    private final AgentTraceRecorder traceRecorder;
    private final ObjectMapper objectMapper;
    private final AgentConversationService conversationService;

    @Autowired
    public AgentHarness(
            AgentRunRepository runRepository,
            AgentToolRegistry toolRegistry,
            AgentPolicyEngine policyEngine,
            AgentTraceRecorder traceRecorder,
            ObjectMapper objectMapper,
            AgentConversationService conversationService) {
        this.runRepository = runRepository;
        this.toolRegistry = toolRegistry;
        this.policyEngine = policyEngine;
        this.traceRecorder = traceRecorder;
        this.objectMapper = objectMapper;
        this.conversationService = conversationService;
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

        AgentConversation conversation = conversationService == null ? null
                : conversationService.resolve(conversationId, operatorId, userInput);
        var conversationHistory = conversation == null ? List.<com.example.demo.agent.model.AgentChatMessage>of()
                : conversationService.context(conversation.getId());

        AgentRun run = new AgentRun();
        run.setOperatorId(operatorId);
        run.setStatus(AgentRunStatus.RUNNING);
        run.setCurrentStep(0);
        run.setInputText(userInput.trim());
        run.setStartedAt(LocalDateTime.now());
        run.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        run = runRepository.save(run);
        if (conversation != null) {
            conversationService.attachRun(conversation, run.getId());
        }

        AgentRunContext context = new AgentRunContext(
                run.getId(), operatorId, modelInput.trim(), conversationHistory);
        return continueRun(run, context, gateway, false);
    }

    private AgentRunResult continueRun(
            AgentRun run,
            AgentRunContext context,
            AgentModelGateway gateway,
            boolean writeAlreadySucceeded) {
        Set<String> executedCalls = fingerprints(context);
        int totalToolCalls = context.getToolResults().size();
        try {
            int firstStep = Math.max(run.getCurrentStep() == null ? 0 : run.getCurrentStep(), 0) + 1;
            for (int step = firstStep; step <= MAX_MODEL_STEPS; step++) {
                run.setCurrentStep(step);
                run = runRepository.save(run);
                AgentModelResponse response = gateway.respond(context, toolRegistry.definitions());
                if (response == null) {
                    throw new IllegalStateException("模型返回为空");
                }
                if (response.hasFinalOutput()) {
                    run.setStatus(AgentRunStatus.COMPLETED);
                    run.setOutputText(response.getFinalOutput());
                    run.setCompletedAt(LocalDateTime.now());
                    run = runRepository.save(run);
                    return result(run, null);
                }
                if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
                    throw new IllegalStateException("模型既未返回结果，也未请求工具");
                }
                for (AgentToolCall call : response.getToolCalls()) {
                    totalToolCalls++;
                    if (totalToolCalls > MAX_TOOL_CALLS) {
                        throw new IllegalStateException("Agent 工具调用次数超过限制");
                    }
                    String fingerprint = call.getName() + ":" + toJson(call.getArguments());
                    if (!executedCalls.add(fingerprint)) {
                        throw new IllegalStateException("检测到重复工具调用：" + call.getName());
                    }
                    AgentTool tool = toolRegistry.require(call.getName());
                    AgentToolContext toolContext = new AgentToolContext(run.getId(), context.getOperatorId());
                    AgentPolicyDecision decision = policyEngine.evaluate(toolContext, tool, call.getArguments());
                    if (decision == AgentPolicyDecision.REQUIRE_APPROVAL) {
                        Object approvalPreview = tool.approvalPreview(toolContext, call.getArguments());
                        run.setStatus(AgentRunStatus.WAITING_APPROVAL);
                        run.setPendingToolCallId(call.getId());
                        run.setPendingToolName(call.getName());
                        run.setPendingArgumentsJson(toJson(call.getArguments()));
                        run.setPendingPreviewJson(toJson(approvalPreview));
                        run.setContextJson(toJson(context.getToolResults()));
                        run.setOutputText("即将执行“" + tool.definition().getDescription()
                                + "”，请确认。影响预览：" + toJson(approvalPreview));
                        run = runRepository.save(run);
                        return result(run, pendingAction(run, tool, approvalPreview));
                    }
                    long started = System.currentTimeMillis();
                    try {
                        Object result = tool.execute(toolContext, call.getArguments());
                        traceRecorder.record(run.getId(), call, result, "SUCCESS",
                                System.currentTimeMillis() - started);
                        context.addToolResult(call.getId(), call.getName(), call.getArguments(), result);
                    } catch (RuntimeException exception) {
                        traceRecorder.record(run.getId(), call, exception.getMessage(), "FAILED",
                                System.currentTimeMillis() - started);
                        throw exception;
                    }
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
            run = runRepository.save(run);
            return result(run, null);
        }
    }

    public AgentRunResult resolveApproval(
            Long runId,
            Long operatorId,
            boolean approved,
            AgentModelGateway gateway) {
        LocalDateTime now = LocalDateTime.now();
        int claimed = runRepository.claimApproval(runId, operatorId,
                AgentRunStatus.WAITING_APPROVAL, AgentRunStatus.RUNNING, now);
        if (claimed != 1) {
            AgentRun existing = runRepository.findById(runId)
                    .orElseThrow(() -> new IllegalArgumentException("Agent 任务不存在"));
            if (!operatorId.equals(existing.getOperatorId())) {
                throw new IllegalArgumentException("无权处理其他操作人的 Agent 任务");
            }
            if (existing.getStatus() == AgentRunStatus.WAITING_APPROVAL
                    && (existing.getExpiresAt() == null || !existing.getExpiresAt().isAfter(now))) {
                existing.setStatus(AgentRunStatus.EXPIRED);
                existing.setCompletedAt(now);
                existing.setOutputText("确认已超时，请重新发起任务");
                runRepository.save(existing);
                return result(existing, null);
            }
            throw new IllegalArgumentException("Agent 任务已被处理或当前不等待确认");
        }
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("已抢占的 Agent 任务不存在"));
        if (!approved) {
            run.setStatus(AgentRunStatus.CANCELLED);
            run.setCompletedAt(LocalDateTime.now());
            run.setOutputText("用户已取消本次操作");
            clearPending(run);
            run = runRepository.save(run);
            return result(run, null);
        }

        AgentTool tool = toolRegistry.require(run.getPendingToolName());
        Map<String, Object> arguments = readArguments(run.getPendingArgumentsJson());
        AgentToolCall call = new AgentToolCall(run.getPendingToolCallId(), run.getPendingToolName(), arguments);
        AgentToolContext context = new AgentToolContext(run.getId(), operatorId);
        AgentPolicyDecision decision = policyEngine.evaluate(context, tool, arguments);
        if (decision != AgentPolicyDecision.REQUIRE_APPROVAL) {
            throw new IllegalStateException("待确认工具的风险级别已变化，请重新发起任务");
        }
        long started = System.currentTimeMillis();
        boolean writeSucceeded = false;
        try {
            Object result = tool.execute(context, arguments);
            writeSucceeded = true;
            traceRecorder.record(run.getId(), call, result, "SUCCESS", System.currentTimeMillis() - started);
            AgentRunContext runContext = restoreContext(run);
            runContext.addToolResult(call.getId(), call.getName(), arguments, result);
            run.setStatus(AgentRunStatus.RUNNING);
            run.setOutputText(toJson(result));
            run.setContextJson(toJson(runContext.getToolResults()));
            clearPending(run);
            run = runRepository.save(run);
            if (gateway == null) {
                run.setStatus(AgentRunStatus.COMPLETED);
                run.setCompletedAt(LocalDateTime.now());
                run.setOutputText("操作已成功执行。结果：" + toJson(result));
                run = runRepository.save(run);
                return result(run, null);
            }
            return continueRun(run, runContext, gateway, true);
        } catch (RuntimeException exception) {
            if (!writeSucceeded) {
                traceRecorder.record(run.getId(), call, exception.getMessage(), "FAILED",
                        System.currentTimeMillis() - started);
            }
            run.setStatus(writeSucceeded ? AgentRunStatus.COMPLETED : AgentRunStatus.FAILED);
            run.setErrorCode(exception.getClass().getSimpleName());
            run.setOutputText(writeSucceeded
                    ? "操作已成功执行，但后续处理失败：" + exception.getMessage()
                    : exception.getMessage());
            run.setCompletedAt(LocalDateTime.now());
            clearPending(run);
            run = runRepository.save(run);
            return result(run, null);
        }
    }

    public AgentRunResult getRun(Long runId, Long operatorId) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 任务不存在"));
        if (!operatorId.equals(run.getOperatorId())) {
            throw new IllegalArgumentException("无权查看其他操作人的 Agent 任务");
        }
        if (run.getStatus() != AgentRunStatus.WAITING_APPROVAL) {
            return result(run, null);
        }
        AgentTool tool = toolRegistry.require(run.getPendingToolName());
        return result(run, pendingAction(run, tool, readJsonValue(run.getPendingPreviewJson())));
    }

    private AgentRunContext restoreContext(AgentRun run) {
        var history = conversationService == null ? List.<com.example.demo.agent.model.AgentChatMessage>of()
                : conversationService.contextBeforeRun(run.getId());
        AgentRunContext context = new AgentRunContext(
                run.getId(), run.getOperatorId(), run.getInputText(), history);
        if (!StringUtils.hasText(run.getContextJson())) {
            return context;
        }
        try {
            List<Map<String, Object>> entries = objectMapper.readValue(
                    run.getContextJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
            for (Map<String, Object> entry : entries) {
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = (Map<String, Object>) entry.get("arguments");
                context.addToolResult(
                        String.valueOf(entry.get("toolCallId")),
                        String.valueOf(entry.get("toolName")),
                        arguments,
                        entry.get("result"));
            }
            return context;
        } catch (JsonProcessingException | ClassCastException exception) {
            throw new IllegalStateException("Agent 上下文损坏", exception);
        }
    }

    private Set<String> fingerprints(AgentRunContext context) {
        Set<String> fingerprints = new HashSet<>();
        for (Map<String, Object> entry : context.getToolResults()) {
            fingerprints.add(entry.get("toolName") + ":" + toJson(entry.get("arguments")));
        }
        return fingerprints;
    }

    private Map<String, Object> readArguments(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("待确认工具参数损坏", exception);
        }
    }

    private void clearPending(AgentRun run) {
        run.setPendingToolCallId(null);
        run.setPendingToolName(null);
        run.setPendingArgumentsJson(null);
        run.setPendingPreviewJson(null);
    }

    private AgentRunResult result(AgentRun run, AgentPendingAction pendingAction) {
        AgentRunResult result = new AgentRunResult(
                run.getId(), run.getStatus(), run.getOutputText(), pendingAction);
        if (conversationService != null) {
            result.setConversationId(conversationService.conversationIdForRun(run.getId()));
            conversationService.touchByRunId(run.getId());
        }
        return result;
    }

    private AgentPendingAction pendingAction(AgentRun run, AgentTool tool, Object preview) {
        String title = switch (tool.definition().getName()) {
            case "create_sales_draft" -> "确认创建销售草稿";
            case "update_sales_draft" -> "确认修改销售草稿";
            case "commit_sales_draft" -> "确认提交正式销售单";
            default -> "确认执行操作";
        };
        return new AgentPendingAction(
                run.getPendingToolCallId(),
                run.getPendingToolName(),
                tool.definition().getRisk(),
                title,
                tool.definition().getDescription(),
                preview,
                run.getExpiresAt());
    }

    private Object readJsonValue(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 确认预览损坏", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工具参数无法序列化", exception);
        }
    }
}
