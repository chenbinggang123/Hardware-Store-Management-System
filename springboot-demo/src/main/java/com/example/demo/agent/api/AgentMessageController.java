package com.example.demo.agent.api;

import com.example.demo.agent.dto.AgentMessageRequest;
import com.example.demo.agent.dto.AgentApprovalRequest;
import com.example.demo.agent.dto.AgentConversationDetail;
import com.example.demo.agent.dto.AgentConversationSummary;
import com.example.demo.agent.dto.AgentAttachmentResponse;
import com.example.demo.agent.dto.CloudAttachmentRequest;
import com.example.demo.agent.harness.AgentHarness;
import com.example.demo.agent.harness.AgentRunResult;
import com.example.demo.agent.model.AgentModelGateway;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.service.AgentAttachmentService;
import com.example.demo.common.ApiResponse;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.security.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/agent")
public class AgentMessageController {
    private final AgentHarness harness;
    private final ObjectProvider<AgentModelGateway> gatewayProvider;
    private final AgentConversationService conversationService;
    private final AgentAttachmentService attachmentService;

    public AgentMessageController(
            AgentHarness harness,
            ObjectProvider<AgentModelGateway> gatewayProvider,
            AgentConversationService conversationService,
            AgentAttachmentService attachmentService) {
        this.harness = harness;
        this.gatewayProvider = gatewayProvider;
        this.conversationService = conversationService;
        this.attachmentService = attachmentService;
    }

    @PostMapping("/messages")
    public ApiResponse<AgentRunResult> send(
            @RequestBody AgentMessageRequest request,
            HttpServletRequest httpRequest) {
        AgentModelGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new IllegalArgumentException("Agent 模型尚未启用");
        }
        Long operatorId = currentUserId(httpRequest);
        Long conversationId = attachmentService.resolveConversationId(
                request == null ? null : request.getConversationId(),
                request == null ? null : request.getAttachmentIds(), operatorId);
        String displayInput = request == null ? null : request.getContent();
        if (!org.springframework.util.StringUtils.hasText(displayInput)
                && request != null && request.getAttachmentIds() != null && !request.getAttachmentIds().isEmpty()) {
            displayInput = "请识别附件中的订单信息并生成销售草稿";
        }
        String modelInput = attachmentService.enrichPrompt(displayInput,
                request == null ? null : request.getAttachmentIds(), conversationId, operatorId);
        AgentRunResult result = harness.run(displayInput, modelInput, operatorId, gateway, conversationId);
        attachmentService.bindToRun(request == null ? null : request.getAttachmentIds(), result.getRunId(),
                result.getConversationId(), operatorId);
        return ApiResponse.ok("Agent 任务执行完成", result);
    }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AgentAttachmentResponse> uploadAttachment(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Long conversationId,
            HttpServletRequest request) {
        return ApiResponse.ok("附件上传完成",
                attachmentService.upload(file, conversationId, currentUserId(request)));
    }

    @PostMapping("/attachments/cloud")
    public ApiResponse<AgentAttachmentResponse> uploadCloudAttachment(
            @RequestBody CloudAttachmentRequest cloudRequest,
            HttpServletRequest request) {
        return ApiResponse.ok("附件上传完成",
                attachmentService.uploadCloud(cloudRequest, currentUserId(request)));
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public ApiResponse<Void> deleteAttachment(
            @PathVariable Long attachmentId,
            HttpServletRequest request) {
        attachmentService.deleteUnsent(attachmentId, currentUserId(request));
        return ApiResponse.ok("附件已删除", null);
    }

    @GetMapping("/conversations")
    public ApiResponse<List<AgentConversationSummary>> conversations(HttpServletRequest request) {
        return ApiResponse.ok(conversationService.list(currentUserId(request)));
    }

    @GetMapping("/conversations/{conversationId}")
    public ApiResponse<AgentConversationDetail> conversation(
            @PathVariable Long conversationId,
            HttpServletRequest request) {
        return ApiResponse.ok(conversationService.detail(conversationId, currentUserId(request)));
    }

    @PostMapping("/runs/{runId}/approval")
    public ApiResponse<AgentRunResult> resolveApproval(
            @PathVariable Long runId,
            @RequestBody AgentApprovalRequest request,
            HttpServletRequest httpRequest) {
        if (request == null) {
            throw new IllegalArgumentException("确认参数不能为空");
        }
        AgentModelGateway gateway = request.isApproved() ? gatewayProvider.getIfAvailable() : null;
        return ApiResponse.ok("Agent 确认已处理",
                harness.resolveApproval(runId, currentUserId(httpRequest), request.isApproved(), gateway));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<AgentRunResult> getRun(
            @PathVariable Long runId,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(harness.getRun(runId, currentUserId(httpRequest)));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE);
        if (!(value instanceof AuthSession session) || session.getUser() == null) {
            throw new IllegalArgumentException("无法识别当前操作人");
        }
        return session.getUser().getId();
    }
}
