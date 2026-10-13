package com.example.demo.filecenter;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.filecenter.dto.FileCenterView;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileCenterServiceTest {
    @Test
    void aggregatesFiltersAndSortsOwnedFiles() {
        AgentAttachmentRepository attachmentRepository = mock(AgentAttachmentRepository.class);
        ExcelTaskRepository taskRepository = mock(ExcelTaskRepository.class);
        ExcelTaskOutputRepository outputRepository = mock(ExcelTaskOutputRepository.class);
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

        when(attachmentRepository.findByOperatorIdOrderByUpdateTimeDesc(9L)).thenReturn(List.of(attachment));
        when(taskRepository.findByOperatorIdOrderByUpdateTimeDesc(9L)).thenReturn(List.of(task));
        when(outputRepository.findByOperatorIdAndSourceTypeOrderByCreateTimeDesc(9L,
                AgentOutputService.AGENT_SOURCE_TYPE)).thenReturn(List.of(output));

        FileCenterService service = new FileCenterService(attachmentRepository, taskRepository, outputRepository);
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
        });
    }
}
