package com.example.demo.agent;

import com.example.demo.agent.entity.AgentConversation;
import com.example.demo.agent.entity.AgentConversationRun;
import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.harness.AgentRunStatus;
import com.example.demo.agent.repository.AgentConversationRepository;
import com.example.demo.agent.repository.AgentConversationRunRepository;
import com.example.demo.agent.repository.AgentRunRepository;
import com.example.demo.agent.service.AgentConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentConversationServiceTest {
    private AgentConversationRepository conversationRepository;
    private AgentConversationRunRepository linkRepository;
    private AgentRunRepository runRepository;
    private AgentConversationService service;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(AgentConversationRepository.class);
        linkRepository = mock(AgentConversationRunRepository.class);
        runRepository = mock(AgentRunRepository.class);
        service = new AgentConversationService(conversationRepository, linkRepository, runRepository);
    }

    @Test
    void createsConversationWithReadableTruncatedTitle() {
        when(conversationRepository.save(any(AgentConversation.class))).thenAnswer(invocation -> {
            AgentConversation conversation = invocation.getArgument(0);
            conversation.setId(12L);
            return conversation;
        });

        AgentConversation conversation = service.resolve(null, 7L,
                "  给陈师傅创建一个包含东成充电电钻、德力西空气开关和绝缘胶布并且记录已收金额的销售草稿  ");

        assertEquals(12L, conversation.getId());
        assertEquals(7L, conversation.getOperatorId());
        assertEquals(31, conversation.getTitle().length());
        assertEquals(true, conversation.getTitle().endsWith("…"));
    }

    @Test
    void rejectsConversationOwnedByAnotherOperator() {
        when(conversationRepository.findByIdAndOperatorId(9L, 7L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.resolve(9L, 7L, "继续"));
    }

    @Test
    void contextKeepsOnlyLatestTenRunsInOriginalOrder() {
        List<AgentConversationRun> links = new ArrayList<>();
        List<AgentRun> runs = new ArrayList<>();
        for (long id = 1; id <= 12; id++) {
            AgentConversationRun link = new AgentConversationRun();
            link.setConversationId(3L);
            link.setRunId(id);
            link.setCreateTime(LocalDateTime.now().plusSeconds(id));
            links.add(link);

            AgentRun run = new AgentRun();
            run.setId(id);
            run.setInputText("问题" + id);
            run.setOutputText("回答" + id);
            run.setStatus(AgentRunStatus.COMPLETED);
            runs.add(run);
        }
        when(linkRepository.findByConversationIdOrderByCreateTimeAsc(3L)).thenReturn(links);
        when(runRepository.findAllById(any())).thenReturn(runs);

        var history = service.context(3L);

        assertEquals(20, history.size());
        assertEquals("问题3", history.get(0).content());
        assertEquals("回答12", history.get(19).content());
    }

    @Test
    void detailReturnsUserAndAssistantMessages() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(3L);
        conversation.setOperatorId(7L);
        conversation.setTitle("库存查询");
        when(conversationRepository.findByIdAndOperatorId(3L, 7L)).thenReturn(Optional.of(conversation));

        AgentConversationRun link = new AgentConversationRun();
        link.setConversationId(3L);
        link.setRunId(20L);
        when(linkRepository.findByConversationIdOrderByCreateTimeAsc(3L)).thenReturn(List.of(link));

        AgentRun run = new AgentRun();
        run.setId(20L);
        run.setInputText("查电钻库存");
        run.setOutputText("库存 24 台");
        run.setStatus(AgentRunStatus.COMPLETED);
        when(runRepository.findAllById(any())).thenReturn(List.of(run));

        var detail = service.detail(3L, 7L);

        assertEquals("库存查询", detail.getTitle());
        assertEquals(2, detail.getMessages().size());
        assertEquals("user", detail.getMessages().get(0).getRole());
        assertEquals("assistant", detail.getMessages().get(1).getRole());
    }

    @Test
    void deletesConversationLinksButKeepsCompletedRunAudit() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(3L);
        conversation.setOperatorId(7L);
        when(conversationRepository.findByIdAndOperatorId(3L, 7L)).thenReturn(Optional.of(conversation));

        AgentConversationRun link = new AgentConversationRun();
        link.setConversationId(3L);
        link.setRunId(20L);
        when(linkRepository.findByConversationIdOrderByCreateTimeAsc(3L)).thenReturn(List.of(link));
        AgentRun run = new AgentRun();
        run.setId(20L);
        run.setStatus(AgentRunStatus.COMPLETED);
        when(runRepository.findAllById(any())).thenReturn(List.of(run));

        service.delete(3L, 7L);

        verify(linkRepository).deleteByConversationId(3L);
        verify(conversationRepository).delete(conversation);
        verify(runRepository, never()).delete(any());
    }

    @Test
    void refusesToDeleteConversationWaitingForApproval() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(3L);
        conversation.setOperatorId(7L);
        when(conversationRepository.findByIdAndOperatorId(3L, 7L)).thenReturn(Optional.of(conversation));
        AgentConversationRun link = new AgentConversationRun();
        link.setConversationId(3L);
        link.setRunId(20L);
        when(linkRepository.findByConversationIdOrderByCreateTimeAsc(3L)).thenReturn(List.of(link));
        AgentRun run = new AgentRun();
        run.setId(20L);
        run.setStatus(AgentRunStatus.WAITING_APPROVAL);
        when(runRepository.findAllById(any())).thenReturn(List.of(run));

        assertThrows(IllegalArgumentException.class, () -> service.delete(3L, 7L));

        verify(linkRepository, never()).deleteByConversationId(3L);
        verify(conversationRepository, never()).delete(any());
    }
}
