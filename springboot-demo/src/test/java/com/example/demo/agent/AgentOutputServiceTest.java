package com.example.demo.agent;

import com.example.demo.agent.dto.AgentFileView;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOutputServiceTest {
    private ExcelTaskOutputRepository outputs;
    private AttachmentStorage storage;
    private AgentOutputService service;

    @BeforeEach
    void setUp() {
        outputs = mock(ExcelTaskOutputRepository.class);
        storage = mock(AttachmentStorage.class);
        service = new AgentOutputService(outputs, storage, new ObjectMapper());
    }

    @Test
    void deleteRemovesStorageObjectAndRecordForOwner() {
        ExcelTaskOutput output = agentFile(31L, 2L, "READY");
        when(outputs.findByIdAndOperatorId(31L, 2L)).thenReturn(Optional.of(output));

        service.delete(31L, 2L);

        verify(storage).delete("agent-outputs/2/202610/31-abcd.xlsx");
        verify(outputs).delete(output);
    }

    @Test
    void deleteStillRemovesRecordWhenStorageFails() {
        ExcelTaskOutput output = agentFile(32L, 2L, "READY");
        when(outputs.findByIdAndOperatorId(32L, 2L)).thenReturn(Optional.of(output));
        doThrow(new IllegalStateException("附件删除失败")).when(storage).delete(anyString());

        service.delete(32L, 2L);

        verify(outputs).delete(output);
    }

    @Test
    void deleteRejectsOtherUsersAndTaskOutputs() {
        when(outputs.findByIdAndOperatorId(33L, 9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(33L, 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");

        ExcelTaskOutput taskOutput = agentFile(34L, 2L, "READY");
        taskOutput.setSourceType("EXCEL_TASK");
        taskOutput.setTaskId(8L);
        when(outputs.findByIdAndOperatorId(34L, 2L)).thenReturn(Optional.of(taskOutput));
        assertThatThrownBy(() -> service.delete(34L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能删除小五生成的文件");
        verify(storage, never()).delete(anyString());
    }

    @Test
    void requireReadyRejectsUnfinishedFiles() {
        ExcelTaskOutput failed = agentFile(35L, 2L, "FAILED");
        when(outputs.findByIdAndOperatorId(35L, 2L)).thenReturn(Optional.of(failed));
        assertThatThrownBy(() -> service.requireReady(35L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未生成完成");

        ExcelTaskOutput ready = agentFile(36L, 2L, "READY");
        when(outputs.findByIdAndOperatorId(36L, 2L)).thenReturn(Optional.of(ready));
        assertThat(service.requireReady(36L, 2L)).isSameAs(ready);
    }

    @Test
    void listsOnlyAgentFilesWithDownloadPaths() {
        ExcelTaskOutput output = agentFile(37L, 2L, "READY");
        output.setSummaryJson("{\"rowCount\":7,\"mismatchCount\":1}");
        when(outputs.findByOperatorIdAndSourceTypeOrderByCreateTimeDesc(2L, AgentOutputService.AGENT_SOURCE_TYPE))
                .thenReturn(List.of(output));

        List<AgentFileView> files = service.listMine(2L);

        assertThat(files).hasSize(1);
        assertThat(files.get(0).downloadPath()).isEqualTo("/agent/outputs/37/download");
        assertThat(files.get(0).expiresAt()).isNull();
        assertThat(files.get(0).summary()).containsEntry("rowCount", 7);
    }

    private ExcelTaskOutput agentFile(Long id, Long operatorId, String status) {
        ExcelTaskOutput output = new ExcelTaskOutput();
        output.setId(id);
        output.setOperatorId(operatorId);
        output.setSourceType(AgentOutputService.AGENT_SOURCE_TYPE);
        output.setRunId(7L);
        output.setOutputType("AGENT_XLSX");
        output.setObjectKey("agent-outputs/2/202610/" + id + "-abcd.xlsx");
        output.setOriginalName("收据-20261012-0900.xlsx");
        output.setMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        output.setFileSize(1234L);
        output.setStatus(status);
        output.setCreateTime(LocalDateTime.now());
        return output;
    }
}
