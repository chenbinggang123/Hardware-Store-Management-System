package com.example.demo.agent;

import com.example.demo.agent.entity.AgentConversation;
import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.harness.AgentHarness;
import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.model.AgentChatMessage;
import com.example.demo.agent.model.AgentModelGateway;
import com.example.demo.agent.model.AgentModelResponse;
import com.example.demo.agent.policy.AgentPolicyEngine;
import com.example.demo.agent.repository.AgentRunRepository;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.tool.AgentToolRegistry;
import com.example.demo.agent.trace.AgentTraceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentHarnessConversationTest {

    @Test
    void continuingConversationPassesHistoryToModel() {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        when(runRepository.save(any(AgentRun.class))).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(0);
            if (run.getId() == null) run.setId(88L);
            return run;
        });
        AgentToolRegistry toolRegistry = mock(AgentToolRegistry.class);
        when(toolRegistry.definitions()).thenReturn(List.of());
        AgentConversationService conversationService = mock(AgentConversationService.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId(9L);
        conversation.setOperatorId(7L);
        when(conversationService.resolve(9L, 7L, "那它的库存呢？")).thenReturn(conversation);
        when(conversationService.context(9L)).thenReturn(List.of(
                new AgentChatMessage("user", "查一下东成电钻"),
                new AgentChatMessage("assistant", "找到东成充电电钻")));
        when(conversationService.conversationIdForRun(88L)).thenReturn(9L);

        AtomicReference<AgentRunContext> captured = new AtomicReference<>();
        AgentModelGateway gateway = (context, tools) -> {
            captured.set(context);
            return AgentModelResponse.finalAnswer("库存 24 台");
        };
        AgentHarness harness = new AgentHarness(
                runRepository,
                toolRegistry,
                mock(AgentPolicyEngine.class),
                mock(AgentTraceRecorder.class),
                new ObjectMapper(),
                conversationService);

        var result = harness.run("那它的库存呢？", 7L, gateway, 9L);

        assertEquals(9L, result.getConversationId());
        assertEquals(2, captured.get().getConversationHistory().size());
        assertEquals("找到东成充电电钻", captured.get().getConversationHistory().get(1).content());
        assertEquals("那它的库存呢？", captured.get().getUserInput());
    }
}
