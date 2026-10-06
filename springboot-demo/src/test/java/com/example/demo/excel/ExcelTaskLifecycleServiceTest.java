package com.example.demo.excel;

import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.repository.ExcelComparisonItemRepository;
import com.example.demo.excel.repository.ExcelComparisonRepository;
import com.example.demo.excel.repository.ExcelTaskCommitRepository;
import com.example.demo.excel.repository.ExcelTaskMappingRepository;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.excel.repository.ExcelTaskRowRepository;
import com.example.demo.excel.repository.ExcelTaskRowResultRepository;
import com.example.demo.excel.repository.ExcelTaskSheetRepository;
import com.example.demo.excel.service.ExcelTaskLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExcelTaskLifecycleServiceTest {
    private final ExcelTaskRepository taskRepository = mock(ExcelTaskRepository.class);
    private final ExcelTaskSheetRepository sheetRepository = mock(ExcelTaskSheetRepository.class);
    private final ExcelTaskRowRepository rowRepository = mock(ExcelTaskRowRepository.class);
    private final ExcelTaskMappingRepository mappingRepository = mock(ExcelTaskMappingRepository.class);
    private final ExcelTaskRowResultRepository resultRepository = mock(ExcelTaskRowResultRepository.class);
    private final ExcelTaskCommitRepository commitRepository = mock(ExcelTaskCommitRepository.class);
    private final ExcelTaskOutputRepository outputRepository = mock(ExcelTaskOutputRepository.class);
    private final ExcelComparisonRepository comparisonRepository = mock(ExcelComparisonRepository.class);
    private final ExcelComparisonItemRepository comparisonItemRepository = mock(ExcelComparisonItemRepository.class);
    private final AttachmentStorage storage = mock(AttachmentStorage.class);
    private ExcelTaskLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new ExcelTaskLifecycleService(taskRepository, sheetRepository, rowRepository, mappingRepository,
                resultRepository, commitRepository, outputRepository, comparisonRepository,
                comparisonItemRepository, storage);
    }

    @Test
    void rewindsReviewTaskToMappingWithOptimisticVersionCheck() {
        ExcelTask task = task("READY_FOR_REVIEW", 4L);
        when(taskRepository.findForUpdate(10L, 2L)).thenReturn(Optional.of(task));

        service.rewindToMapping(10L, 4L, 2L);

        assertEquals("READY_FOR_MAPPING", task.getStatus());
        verify(taskRepository).saveAndFlush(task);
    }

    @Test
    void deletesPendingTaskAndItsDerivedData() {
        ExcelTask task = task("READY_FOR_REVIEW", 4L);
        task.setObjectKey("excel-tasks/2/source.xlsx");
        ExcelTaskOutput output = new ExcelTaskOutput();
        output.setObjectKey("excel-outputs/2/quote.xlsx");
        output.setStatus("READY");
        when(taskRepository.findForUpdate(10L, 2L)).thenReturn(Optional.of(task));
        when(outputRepository.findByTaskId(10L)).thenReturn(List.of(output));
        when(comparisonRepository.findByBaseTaskIdOrNewTaskId(10L, 10L)).thenReturn(List.of());

        service.delete(10L, 2L);

        verify(resultRepository).deleteByTaskId(10L);
        verify(mappingRepository).deleteByTaskId(10L);
        verify(rowRepository).deleteByTaskId(10L);
        verify(sheetRepository).deleteByTaskId(10L);
        verify(taskRepository).delete(task);
        verify(storage).delete("excel-outputs/2/quote.xlsx");
        verify(storage).delete("excel-tasks/2/source.xlsx");
    }

    @Test
    void keepsCommittedTaskForAuditTrail() {
        ExcelTask task = task("COMMITTED", 5L);
        when(taskRepository.findForUpdate(10L, 2L)).thenReturn(Optional.of(task));

        assertThrows(IllegalArgumentException.class, () -> service.delete(10L, 2L));

        verify(taskRepository, never()).delete(task);
    }

    private ExcelTask task(String status, Long version) {
        ExcelTask task = new ExcelTask();
        task.setId(10L);
        task.setOperatorId(2L);
        task.setStatus(status);
        task.setVersion(version);
        return task;
    }
}
