package com.example.demo.agent.api;

import com.example.demo.agent.dto.AgentMessageRequest;
import com.example.demo.agent.dto.AgentApprovalRequest;
import com.example.demo.agent.harness.AgentHarness;
import com.example.demo.agent.harness.AgentRunResult;
import com.example.demo.agent.model.AgentModelGateway;
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

@RestController
@RequestMapping("/agent")
public class AgentMessageController {
    private final AgentHarness harness;
    private final ObjectProvider<AgentModelGateway> gatewayProvider;

    public AgentMessageController(AgentHarness harness, ObjectProvider<AgentModelGateway> gatewayProvider) {
        this.harness = harness;
        this.gatewayProvider = gatewayProvider;
    }

    @PostMapping("/messages")
    public ApiResponse<AgentRunResult> send(
            @RequestBody AgentMessageRequest request,
            HttpServletRequest httpRequest) {
        AgentModelGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new IllegalArgumentException("Agent 模型尚未启用");
        }
        return ApiResponse.ok("Agent 任务执行完成",
                harness.run(request == null ? null : request.getContent(), currentUserId(httpRequest), gateway));
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
