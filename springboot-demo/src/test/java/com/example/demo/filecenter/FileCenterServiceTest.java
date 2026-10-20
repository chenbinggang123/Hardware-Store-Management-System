package com.example.demo.filecenter;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.filecenter.dto.FileCenterView;
import com.example.demo.filecenter.dto.FileCenterDetail;
import com.example.demo.filecenter.dto.FileCenterUpdateRequest;
import com.example.demo.filecenter.entity.FileCenterActivity;
import com.example.demo.filecenter.entity.FileCenterMetadata;
import com.example.demo.filecenter.repository.FileCenterActivityRepository;
import com.example.demo.filecenter.repository.FileCenterMetadataRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class FileCenterServiceTest {
    @Test
    void aggregatesFiltersAndSortsOwnedFiles() {
        AgentAttachmentRepository attachmentRepository = mock(AgentAttachmentRepository.class);
        ExcelTaskRepository taskRepository = mock(ExcelTaskRepository.class);
        ExcelTaskOutputRepository outputRepository = mock(ExcelTaskOutputRepository.class);
        FileCenterMetadataRepository metadataRepository = mock(FileCenterMetadataRepository.class);
        FileCenterActivityRepository activityRepository = mock(FileCenterActivityRepository.class);
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 10, 0);

        AgentAttachment attachment = new AgentAttachment();
        attachment.setId(1L);
        attachment.setOperatorId(9L);
        attachment.setOriginalName("送货单照片.jpg");
        attachment.setMimeType("image/jpeg");
        attachment.setParseStatus("PARSED");
        attachment.setConversationId(11L);
        attachment.setRunId(12L);
        attachment.setCreateTime(now.minusHours(2));
        attachment.setUpdateTime(now.minusHours(1));

        AgentAttachment conversationOnlyAttachment = new AgentAttachment();
        conversationOnlyAttachment.setId(4L);
        conversationOnlyAttachment.setOperatorId(9L);
        conversationOnlyAttachment.setOriginalName("仅供识别.jpg");
        conversationOnlyAttachment.setMimeType("image/jpeg");
        conversationOnlyAttachment.setParseStatus("PARSED");
        conversationOnlyAttachment.setConversationId(11L);
        conversationOnlyAttachment.setRunId(12L);
        conversationOnlyAttachment.setCreateTime(now);
        conversationOnlyAttachment.setUpdateTime(now);

        ExcelTask task = new ExcelTask();
        task.setId(2L);
        task.setOriginalName("十月价格表.xlsx");
        task.setPurpose("SUPPLIER_PRICE");
        task.setStatus("READY_FOR_REVIEW");
        task.setCreateTime(now.minusHours(3));
        task.setUpdateTime(now);

        ExcelTaskOutput output = new ExcelTaskOutput();
        output.setId(3L);
        output.setOriginalName("整理结果.xlsx");
        output.setStatus("READY");
        output.setSourceType(AgentOutputService.AGENT_SOURCE_TYPE);
        output.setCreateTime(now.minusMinutes(30));

        when(attachmentRepository.findByOperatorIdOrderByUpdateTimeDesc(9L))
                .thenReturn(List.of(conversationOnlyAttachment, attachment));
        when(taskRepository.findByOperatorIdOrderByUpdateTimeDesc(9L)).thenReturn(List.of(task));
        when(outputRepository.findByOperatorIdAndSourceTypeOrderByCreateTimeDesc(9L,
                AgentOutputService.AGENT_SOURCE_TYPE)).thenReturn(List.of(output));
        FileCenterMetadata manualUpload = new FileCenterMetadata();
        manualUpload.setOperatorId(9L);
        manualUpload.setSourceKind("ATTACHMENT");
        manualUpload.setSourceId(1L);
        manualUpload.setManualUpload(true);
        when(metadataRepository.findByOperatorId(9L)).thenReturn(List.of(manualUpload));

        FileCenterService service = new FileCenterService(attachmentRepository, taskRepository, outputRepository,
                metadataRepository, activityRepository);
        FileCenterView all = service.list(9L, null, null);
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.pending()).isEqualTo(1);
        assertThat(all.completed()).isEqualTo(2);
        assertThat(all.items()).extracting(item -> item.key())
                .containsExactly("task-2", "output-3", "attachment-1");

        FileCenterView filtered = service.list(9L, "pending", "价格");
        assertThat(filtered.items()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("TASK");
            assertThat(item.purpose()).isEqualTo("供应商价格表");
            assertThat(item.organized()).isFalse();
        });
    }

    @Test
    void organizesManualFileAndBuildsTimeline() {
        AgentAttachmentRepository attachmentRepository = mock(AgentAttachmentRepository.class);
        ExcelTaskRepository taskRepository = mock(ExcelTaskRepository.class);
        ExcelTaskOutputRepository outputRepository = mock(ExcelTaskOutputRepository.class);
        FileCenterMetadataRepository metadataRepository = mock(FileCenterMetadataRepository.class);
        FileCenterActivityRepository activityRepository = mock(FileCenterActivityRepository.class);
        AgentAttachment attachment = new AgentAttachment();
        attachment.setId(7L);
        attachment.setOperatorId(9L);
        attachment.setOriginalName("测试业务单.jpg");
        attachment.setMimeType("image/jpeg");
        attachment.setParseStatus("PARSED");
        attachment.setExtractedText("送货单 单号：MOCK-2001 日期 2026年10月14日 合计：￥1,280.50 已收：500");
        attachment.setConversationId(10L);
        attachment.setCreateTime(LocalDateTime.of(2026, 10, 14, 9, 0));
        attachment.setUpdateTime(attachment.getCreateTime());
        when(attachmentRepository.findByIdAndOperatorId(7L, 9L)).thenReturn(Optional.of(attachment));

        AtomicReference<FileCenterMetadata> stored = new AtomicReference<>();
        List<FileCenterActivity> activities = new ArrayList<>();
        when(metadataRepository.findByOperatorIdAndSourceKindAndSourceId(9L, "ATTACHMENT", 7L))
                .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(metadataRepository.save(any(FileCenterMetadata.class))).thenAnswer(invocation -> {
            FileCenterMetadata metadata = invocation.getArgument(0);
            metadata.setId(3L);
            stored.set(metadata);
            return metadata;
        });
        when(activityRepository.save(any(FileCenterActivity.class))).thenAnswer(invocation -> {
            FileCenterActivity activity = invocation.getArgument(0);
            activities.add(activity);
            return activity;
        });
        when(activityRepository.findByMetadataIdOrderByCreateTimeDesc(3L)).thenAnswer(ignored -> activities);

        FileCenterService service = new FileCenterService(attachmentRepository, taskRepository, outputRepository,
                metadataRepository, activityRepository);
        FileCenterUpdateRequest request = new FileCenterUpdateRequest();
        request.setPurposeCode("CUSTOMER_RECEIPT");
        request.setRelatedType("CUSTOMER");
        request.setRelatedId(18L);
        request.setRelatedName("张师傅");
        request.setNote("尚欠 300 元");
        request.setManualUpload(true);

        FileCenterDetail detail = service.update(9L, "attachment", 7L, request);
        assertThat(detail.organized()).isTrue();
        assertThat(detail.manualUpload()).isTrue();
        assertThat(detail.item().purpose()).isEqualTo("客户单据");
        assertThat(detail.item().relatedName()).isEqualTo("张师傅");
        assertThat(detail.accountDraft().documentNumber()).isEqualTo("MOCK-2001");
        assertThat(detail.accountDraft().totalAmount()).isEqualByComparingTo("1280.50");
        assertThat(detail.accountDraft().settledAmount()).isEqualByComparingTo("500");
        assertThat(detail.accountDraft().reviewFields()).isEmpty();
        assertThat(detail.activities()).extracting(item -> item.type()).contains("UPLOADED", "CREATED");
    }
}
