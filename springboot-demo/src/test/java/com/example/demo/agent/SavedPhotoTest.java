package com.example.demo.agent;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.entity.AgentConversation;
import com.example.demo.agent.repository.*;
import com.example.demo.agent.service.*;
import com.example.demo.agent.service.attachment.*;
import com.example.demo.filecenter.entity.FileCenterMetadata;
import com.example.demo.filecenter.repository.FileCenterMetadataRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SavedPhotoTest {
    private final AgentAttachmentRepository attachments = mock(AgentAttachmentRepository.class);
    private final FileCenterMetadataRepository metadata = mock(FileCenterMetadataRepository.class);
    private final AttachmentStorage storage = mock(AttachmentStorage.class);

    private AgentAttachment photo(long id, boolean saved) {
        var photo = new AgentAttachment();
        photo.setId(id); photo.setOperatorId(7L); photo.setConversationId(9L);
        photo.setObjectKey("cloud://photos/" + id); photo.setMimeType("image/jpeg");
        when(attachments.findById(id)).thenReturn(Optional.of(photo));
        when(attachments.findByIdAndOperatorId(id, 7L)).thenReturn(Optional.of(photo));
        var info = new FileCenterMetadata(); info.setManualUpload(saved);
        when(metadata.findByOperatorIdAndSourceKindAndSourceId(7L, "ATTACHMENT", id)).thenReturn(Optional.of(info));
        return photo;
    }

    private AgentAttachmentService service() {
        var service = new AgentAttachmentService(attachments, mock(AgentConversationService.class), storage,
                mock(AttachmentContentParser.class), mock(CloudBaseFileDownloader.class), 1024);
        service.setFileMetadata(metadata);
        return service;
    }

    @Test void savedPhotoSurvivesRemovingPendingAttachment() {
        var photo = photo(1L, true);
        assertFalse(service().deleteUnsent(1L, 7L));
        verify(attachments, never()).delete(photo);
        verifyNoInteractions(storage);
    }

    @Test void temporaryPhotoStillGetsDeleted() {
        var photo = photo(1L, false);
        assertTrue(service().deleteUnsent(1L, 7L));
        verify(attachments).delete(photo);
    }

    @Test void fileCenterUploadSavesOriginalBeforePurposeOrganization() {
        var conversations = mock(AgentConversationService.class);
        var conversation = new AgentConversation(); conversation.setId(9L);
        when(conversations.resolve(null, 7L, "新对话")).thenReturn(conversation);
        var downloader = mock(CloudBaseFileDownloader.class);
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        when(downloader.download("cloud://photo", "https://photo", 1024)).thenReturn(jpeg);
        var parser = mock(AttachmentContentParser.class);
        when(parser.parse("原图.jpg", "image/jpeg", jpeg)).thenReturn(AttachmentParseResult.waiting("等待识别"));
        when(attachments.save(any(AgentAttachment.class))).thenAnswer(call -> {
            AgentAttachment item = call.getArgument(0); item.setId(11L); return item;
        });
        var service = new AgentAttachmentService(attachments, conversations, storage, parser, downloader, 1024);
        service.setFileMetadata(metadata);
        var request = new com.example.demo.agent.dto.CloudAttachmentRequest();
        request.setOriginalName("原图.jpg"); request.setCloudFileId("cloud://photo");
        request.setDownloadUrl("https://photo"); request.setSaveToFiles(true);
        service.uploadCloud(request, 7L);
        var captured = org.mockito.ArgumentCaptor.forClass(FileCenterMetadata.class);
        verify(metadata).save(captured.capture());
        assertEquals(11L, captured.getValue().getSourceId());
        assertEquals(true, captured.getValue().getManualUpload());
        assertEquals("UNCLASSIFIED", captured.getValue().getPurposeCode());
    }

    @Test void savedPhotoOpensIndependentlyOfChatAndOcrStatus() {
        var photo = photo(1L, true); photo.setConversationId(null); photo.setParseStatus("FAILED");
        assertEquals("cloud://photos/1", service().photoSource(1L, 7L));
        assertThrows(IllegalArgumentException.class, () -> service().photoSource(1L, 8L));
        photo(2L, false);
        assertThrows(IllegalArgumentException.class, () -> service().photoSource(2L, 7L));
    }

    @Test void deletingChatRetainsSavedOriginalAndDeletesOnlyTemporaryFiles() {
        var saved = photo(1L, true); saved.setRunId(88L);
        var temporary = photo(2L, false);
        var conversations = mock(AgentConversationRepository.class);
        var links = mock(AgentConversationRunRepository.class);
        var conversation = new AgentConversation(); conversation.setId(9L);
        when(conversations.findByIdAndOperatorId(9L, 7L)).thenReturn(Optional.of(conversation));
        when(attachments.findByConversationIdOrderByCreateTimeAsc(9L)).thenReturn(List.of(saved, temporary));
        var service = new AgentConversationService(conversations, links, mock(AgentRunRepository.class), attachments, storage);
        service.setFileMetadata(metadata);
        assertEquals(List.of("cloud://photos/2"), service.delete(9L, 7L));
        assertNull(saved.getConversationId()); assertNull(saved.getRunId());
        verify(attachments).saveAll(List.of(saved));
        verify(attachments).deleteAll(List.of(temporary));
    }
}
