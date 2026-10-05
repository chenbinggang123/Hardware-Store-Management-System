package com.example.demo.agent.service;

import com.example.demo.agent.dto.AgentConversationDetail;
import com.example.demo.agent.dto.AgentConversationMessage;
import com.example.demo.agent.dto.AgentConversationSummary;
import com.example.demo.agent.entity.AgentConversation;
import com.example.demo.agent.entity.AgentConversationRun;
import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.model.AgentChatMessage;
import com.example.demo.agent.repository.AgentConversationRepository;
import com.example.demo.agent.repository.AgentConversationRunRepository;
import com.example.demo.agent.repository.AgentRunRepository;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.agent.harness.AgentRunStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class AgentConversationService {
    private static final int MAX_CONTEXT_RUNS = 10;

    private final AgentConversationRepository conversationRepository;
    private final AgentConversationRunRepository conversationRunRepository;
    private final AgentRunRepository runRepository;
    private final AgentAttachmentRepository attachmentRepository;
    private final AttachmentStorage attachmentStorage;

    @Autowired
    public AgentConversationService(
            AgentConversationRepository conversationRepository,
            AgentConversationRunRepository conversationRunRepository,
            AgentRunRepository runRepository,
            AgentAttachmentRepository attachmentRepository,
            AttachmentStorage attachmentStorage) {
        this.conversationRepository = conversationRepository;
        this.conversationRunRepository = conversationRunRepository;
        this.runRepository = runRepository;
        this.attachmentRepository = attachmentRepository;
        this.attachmentStorage = attachmentStorage;
    }

    public AgentConversationService(
            AgentConversationRepository conversationRepository,
            AgentConversationRunRepository conversationRunRepository,
            AgentRunRepository runRepository,
            AgentAttachmentRepository attachmentRepository) {
        this(conversationRepository, conversationRunRepository, runRepository, attachmentRepository, null);
    }

    public AgentConversationService(
            AgentConversationRepository conversationRepository,
            AgentConversationRunRepository conversationRunRepository,
            AgentRunRepository runRepository) {
        this(conversationRepository, conversationRunRepository, runRepository, null, null);
    }

    public AgentConversation resolve(Long conversationId, Long operatorId, String firstInput) {
        if (conversationId != null) {
            return conversationRepository.findByIdAndOperatorId(conversationId, operatorId)
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在或无权访问"));
        }
        LocalDateTime now = LocalDateTime.now();
        AgentConversation conversation = new AgentConversation();
        conversation.setOperatorId(operatorId);
        conversation.setTitle(title(firstInput));
        conversation.setCreateTime(now);
        conversation.setUpdateTime(now);
        return conversationRepository.save(conversation);
    }

    public AgentConversation rename(Long conversationId, Long operatorId, String requestedTitle) {
        AgentConversation conversation = conversationRepository.findByIdAndOperatorId(conversationId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或无权访问"));
        String normalized = StringUtils.hasText(requestedTitle)
                ? requestedTitle.trim().replaceAll("\\s+", " ") : "";
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("会话标题不能为空");
        }
        if (normalized.length() > 50) {
            throw new IllegalArgumentException("会话标题不能超过 50 个字符");
        }
        conversation.setTitle(normalized);
        touch(conversation);
        return conversation;
    }

    public void attachRun(AgentConversation conversation, Long runId) {
        AgentConversationRun link = new AgentConversationRun();
        link.setConversationId(conversation.getId());
        link.setRunId(runId);
        link.setCreateTime(LocalDateTime.now());
        conversationRunRepository.save(link);
        touch(conversation);
    }

    public void touchByRunId(Long runId) {
        conversationRunRepository.findByRunId(runId).ifPresent(link ->
                conversationRepository.findById(link.getConversationId()).ifPresent(this::touch));
    }

    @Transactional(readOnly = true)
    public Long conversationIdForRun(Long runId) {
        return conversationRunRepository.findByRunId(runId)
                .map(AgentConversationRun::getConversationId).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<AgentChatMessage> context(Long conversationId) {
        return contextFromRuns(runs(conversationId));
    }

    @Transactional(readOnly = true)
    public List<AgentChatMessage> contextBeforeRun(Long runId) {
        AgentConversationRun current = conversationRunRepository.findByRunId(runId).orElse(null);
        if (current == null) return List.of();
        List<AgentRun> previousRuns = runs(current.getConversationId()).stream()
                .takeWhile(run -> !runId.equals(run.getId())).toList();
        return contextFromRuns(previousRuns);
    }

    private List<AgentChatMessage> contextFromRuns(List<AgentRun> runs) {
        int from = Math.max(0, runs.size() - MAX_CONTEXT_RUNS);
        List<AgentChatMessage> history = new ArrayList<>();
        for (AgentRun run : runs.subList(from, runs.size())) {
            if (StringUtils.hasText(run.getInputText())) {
                history.add(new AgentChatMessage("user", run.getInputText()));
            }
            if (StringUtils.hasText(run.getOutputText()) && run.getStatus() != null) {
                history.add(new AgentChatMessage("assistant", run.getOutputText()));
            }
        }
        return history;
    }

    @Transactional(readOnly = true)
    public List<AgentConversationSummary> list(Long operatorId) {
        return conversationRepository.findTop50ByOperatorIdOrderByUpdateTimeDesc(operatorId).stream()
                .map(conversation -> {
                    List<AgentRun> runs = runs(conversation.getId());
                    AgentRun latest = runs.isEmpty() ? null : runs.get(runs.size() - 1);
                    String preview = latest == null ? "暂无消息"
                            : StringUtils.hasText(latest.getOutputText()) ? latest.getOutputText() : latest.getInputText();
                    return new AgentConversationSummary(conversation.getId(), conversation.getTitle(),
                            abbreviate(preview, 60), conversation.getUpdateTime());
                }).toList();
    }

    @Transactional(readOnly = true)
    public AgentConversationDetail detail(Long conversationId, Long operatorId) {
        AgentConversation conversation = conversationRepository.findByIdAndOperatorId(conversationId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或无权访问"));
        List<AgentConversationMessage> messages = new ArrayList<>();
        for (AgentRun run : runs(conversationId)) {
            if (StringUtils.hasText(run.getInputText())) {
                var attachments = attachmentRepository == null ? List.<com.example.demo.agent.dto.AgentAttachmentResponse>of()
                        : attachmentRepository.findByRunIdOrderByCreateTimeAsc(run.getId()).stream()
                        .map(item -> new com.example.demo.agent.dto.AgentAttachmentResponse(item.getId(), item.getConversationId(),
                                item.getOriginalName(), item.getMimeType(), item.getFileSize(), item.getParseStatus(), item.getErrorMessage()))
                        .toList();
                messages.add(new AgentConversationMessage(run.getId(), "user", run.getInputText(), run.getStatus(), attachments));
            }
            if (StringUtils.hasText(run.getOutputText())) {
                messages.add(new AgentConversationMessage(run.getId(), "assistant", run.getOutputText(), run.getStatus()));
            }
        }
        return new AgentConversationDetail(conversation.getId(), conversation.getTitle(), messages);
    }

    public List<String> delete(Long conversationId, Long operatorId) {
        AgentConversation conversation = conversationRepository.findByIdAndOperatorId(conversationId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或无权访问"));
        boolean hasActiveRun = runs(conversationId).stream().anyMatch(run ->
                run.getStatus() == AgentRunStatus.RUNNING || run.getStatus() == AgentRunStatus.WAITING_APPROVAL);
        if (hasActiveRun) {
            throw new IllegalArgumentException("该会话还有正在处理或等待确认的操作，请先完成或取消操作");
        }
        List<String> cloudFileIds = List.of();
        if (attachmentRepository != null) {
            var attachments = attachmentRepository.findByConversationIdOrderByCreateTimeAsc(conversationId);
            cloudFileIds = attachments.stream()
                    .map(item -> item.getObjectKey())
                    .filter(key -> key != null && key.startsWith("cloud://"))
                    .toList();
            if (attachmentStorage != null) {
                attachments.stream()
                        .filter(item -> item.getObjectKey() != null && !item.getObjectKey().startsWith("cloud://"))
                        .forEach(item -> attachmentStorage.delete(item.getObjectKey()));
            }
            attachmentRepository.deleteAll(attachments);
        }
        conversationRunRepository.deleteByConversationId(conversationId);
        conversationRepository.delete(conversation);
        return cloudFileIds;
    }

    private List<AgentRun> runs(Long conversationId) {
        List<Long> ids = conversationRunRepository.findByConversationIdOrderByCreateTimeAsc(conversationId)
                .stream().map(AgentConversationRun::getRunId).toList();
        if (ids.isEmpty()) return List.of();
        var byId = new java.util.HashMap<Long, AgentRun>();
        runRepository.findAllById(ids).forEach(run -> byId.put(run.getId(), run));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private void touch(AgentConversation conversation) {
        conversation.setUpdateTime(LocalDateTime.now());
        conversationRepository.save(conversation);
    }

    private String title(String input) {
        String normalized = StringUtils.hasText(input) ? input.trim().replaceAll("\\s+", " ") : "新对话";
        return abbreviate(normalized, 30);
    }

    private String abbreviate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) return "暂无消息";
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }
}
