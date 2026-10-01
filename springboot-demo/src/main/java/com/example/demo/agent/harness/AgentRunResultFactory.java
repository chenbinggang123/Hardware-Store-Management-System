package com.example.demo.agent.harness;

import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.tool.AgentTool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AgentRunResultFactory {
    private final AgentConversationService conversationService;

    public AgentRunResultFactory(AgentConversationService conversationService) {
        this.conversationService = conversationService;
    }

    public AgentRunResult create(AgentRun run) {
        return create(run, null);
    }

    public AgentRunResult create(AgentRun run, AgentPendingAction pendingAction) {
        AgentRunResult result = new AgentRunResult(
                run.getId(), run.getStatus(), run.getOutputText(), pendingAction);
        if (conversationService != null) {
            result.setConversationId(conversationService.conversationIdForRun(run.getId()));
            conversationService.touchByRunId(run.getId());
        }
        return result;
    }

    public AgentPendingAction pendingAction(AgentRun run, AgentTool tool, Object preview) {
        String title = StringUtils.hasText(tool.definition().getApprovalTitle())
                ? tool.definition().getApprovalTitle() : "确认执行操作";
        return new AgentPendingAction(
                run.getPendingToolCallId(),
                run.getPendingToolName(),
                tool.definition().getRisk(),
                title,
                tool.definition().getDescription(),
                preview,
                run.getExpiresAt());
    }
}
