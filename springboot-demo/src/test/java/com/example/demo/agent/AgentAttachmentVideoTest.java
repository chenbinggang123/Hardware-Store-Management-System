package com.example.demo.agent;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.entity.AgentConversation;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.AgentAttachmentService;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.service.attachment.AttachmentContentParser;
import com.example.demo.agent.service.attachment.AttachmentParseResult;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.agent.service.attachment.CloudBaseFileDownloader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAttachmentVideoTest {
    @Test
    void acceptsRealMp4SignatureAndQueuesVideoForParsing() {
        AgentAttachmentRepository repository = mock(AgentAttachmentRepository.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        AttachmentStorage storage = mock(AttachmentStorage.class);
        AttachmentContentParser parser = mock(AttachmentContentParser.class);
        CloudBaseFileDownloader downloader = mock(CloudBaseFileDownloader.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId(12L);
        when(conversationService.resolve(null, 7L, "附件：厂商订单.mp4")).thenReturn(conversation);
        when(parser.parse(eq("厂商订单.mp4"), eq("video/mp4"), any(byte[].class)))
                .thenReturn(AttachmentParseResult.waiting("等待 OCR"));
        when(repository.save(any(AgentAttachment.class))).thenAnswer(invocation -> {
            AgentAttachment value = invocation.getArgument(0);
            value.setId(18L);
            return value;
        });
        AgentAttachmentService service = new AgentAttachmentService(
                repository, conversationService, storage, parser, downloader, 10 * 1024 * 1024L);
        byte[] mp4 = new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

        var response = service.upload(new MockMultipartFile(
                "file", "厂商订单.mp4", "video/mp4", mp4), null, 7L);

        assertEquals("video/mp4", response.getMimeType());
        assertEquals("WAITING_OCR", response.getParseStatus());
        ArgumentCaptor<AgentAttachment> captor = ArgumentCaptor.forClass(AgentAttachment.class);
        verify(repository).save(captor.capture());
        assertEquals("video/mp4", captor.getValue().getMimeType());
    }
}
