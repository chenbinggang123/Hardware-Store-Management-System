package com.example.demo.agent.harness;

import com.example.demo.agent.dto.AgentFileView;
import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.agent.tool.AgentTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class AgentRunResultFactory {
    private final AgentConversationService conversationService;
    private final AgentOutputService outputService;

    public AgentRunResultFactory(AgentConversationService conversationService) {
        this(conversationService, null);
    }

    @Autowired
    public AgentRunResultFactory(AgentConversationService conversationService, AgentOutputService outputService) {
        this.conversationService = conversationService;
        this.outputService = outputService;
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
        if (outputService != null && run.getId() != null) {
            result.setFiles(outputService.forRun(run.getId()));
        }
        return result;
    }

    public List<AgentFileView> filesForRun(Long runId) {
        return outputService == null || runId == null ? List.of() : outputService.forRun(runId);
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
