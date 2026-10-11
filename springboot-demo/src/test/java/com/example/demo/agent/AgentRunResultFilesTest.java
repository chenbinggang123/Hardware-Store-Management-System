package com.example.demo.agent;

import com.example.demo.agent.dto.AgentConversationDetail;
import com.example.demo.agent.dto.AgentFileView;
import com.example.demo.agent.entity.AgentConversation;
import com.example.demo.agent.entity.AgentConversationRun;
import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.harness.AgentRunResult;
import com.example.demo.agent.harness.AgentRunResultFactory;
import com.example.demo.agent.harness.AgentRunStatus;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.repository.AgentConversationRepository;
import com.example.demo.agent.repository.AgentConversationRunRepository;
import com.example.demo.agent.repository.AgentRunRepository;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunResultFilesTest {
    private static final AgentFileView FILE = new AgentFileView(5L, "收据.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 120, "READY",
            AgentOutputService.AGENT_SOURCE_TYPE, 88L, 9L, null, LocalDateTime.now(), null,
            "/agent/outputs/5/download", null, Map.of());

    @Test
    void runResultCarriesGeneratedFiles() {
        AgentOutputService outputService = mock(AgentOutputService.class);
        when(outputService.forRun(88L)).thenReturn(List.of(FILE));
        AgentRunResultFactory factory = new AgentRunResultFactory(null, outputService);
        AgentRun run = new AgentRun();
        run.setId(88L);
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setOutputText("文件已生成");

        AgentRunResult result = factory.create(run);

        assertThat(result.getFiles()).containsExactly(FILE);
        assertThat(new AgentRunResultFactory(null).create(run).getFiles()).isEmpty();
    }

    @Test
    void conversationHistoryAttachesFilesToAssistantMessages() {
        AgentConversationRepository conversations = mock(AgentConversationRepository.class);
        AgentConversationRunRepository links = mock(AgentConversationRunRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentAttachmentRepository attachments = mock(AgentAttachmentRepository.class);
        AgentOutputService outputService = mock(AgentOutputService.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId(9L);
        conversation.setOperatorId(2L);
        conversation.setTitle("收据整理");
        AgentConversationRun link = new AgentConversationRun();
        link.setConversationId(9L);
        link.setRunId(88L);
        AgentRun run = new AgentRun();
        run.setId(88L);
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setInputText("把收据做成 Excel");
        run.setOutputText("已生成文件 收据.xlsx");
        when(conversations.findByIdAndOperatorId(9L, 2L)).thenReturn(Optional.of(conversation));
        when(links.findByConversationIdOrderByCreateTimeAsc(9L)).thenReturn(List.of(link));
        when(runs.findAllById(List.of(88L))).thenReturn(List.of(run));
        when(attachments.findByRunIdOrderByCreateTimeAsc(88L)).thenReturn(List.of());
        when(outputService.forRun(88L)).thenReturn(List.of(FILE));
        AgentConversationService service = new AgentConversationService(
                conversations, links, runs, attachments, mock(AttachmentStorage.class), outputService);

        AgentConversationDetail detail = service.detail(9L, 2L);

        assertThat(detail.getMessages()).hasSize(2);
        assertThat(detail.getMessages().get(0).getFiles()).isEmpty();
        assertThat(detail.getMessages().get(1).getRole()).isEqualTo("assistant");
        assertThat(detail.getMessages().get(1).getFiles()).containsExactly(FILE);
    }
}
