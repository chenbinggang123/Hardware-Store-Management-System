package com.example.demo.agent.harness;

import com.example.demo.agent.entity.AgentConversation;
import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.model.AgentChatMessage;
import com.example.demo.agent.service.AgentConversationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AgentContextManager {
    private final ObjectMapper objectMapper;
    private final AgentConversationService conversationService;

    public AgentContextManager(ObjectMapper objectMapper, AgentConversationService conversationService) {
        this.objectMapper = objectMapper;
        this.conversationService = conversationService;
    }

    public AgentStartContext prepareStart(Long conversationId, Long operatorId, String userInput) {
        AgentConversation conversation = conversationService == null ? null
                : conversationService.resolve(conversationId, operatorId, userInput);
        List<AgentChatMessage> history = conversation == null ? List.of()
                : conversationService.context(conversation.getId());
        return new AgentStartContext(conversation, history);
    }

    public AgentRunContext start(AgentRun run, String modelInput, AgentStartContext startContext) {
        if (startContext.conversation() != null) {
            conversationService.attachRun(startContext.conversation(), run.getId());
        }
        return new AgentRunContext(
                run.getId(), run.getOperatorId(), modelInput.trim(), startContext.history());
    }

    public AgentRunContext restore(AgentRun run) {
        List<AgentChatMessage> history = conversationService == null ? List.of()
                : conversationService.contextBeforeRun(run.getId());
        AgentRunContext context = new AgentRunContext(
                run.getId(), run.getOperatorId(), run.getInputText(), history);
        if (!StringUtils.hasText(run.getContextJson())) {
            return context;
        }
        try {
            List<Map<String, Object>> entries = objectMapper.readValue(
                    run.getContextJson(), new TypeReference<>() {
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

    public Set<String> fingerprints(AgentRunContext context) {
        Set<String> fingerprints = new HashSet<>();
        for (Map<String, Object> entry : context.getToolResults()) {
            fingerprints.add(entry.get("toolName") + ":" + toJson(entry.get("arguments")));
        }
        return fingerprints;
    }

    public Map<String, Object> readArguments(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("待确认工具参数损坏", exception);
        }
    }

    public Object readValue(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 确认预览损坏", exception);
        }
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工具参数无法序列化", exception);
        }
    }
}
