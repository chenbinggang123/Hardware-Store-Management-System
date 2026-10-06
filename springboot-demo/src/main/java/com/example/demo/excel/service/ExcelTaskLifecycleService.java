package com.example.demo.excel.service;

import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.excel.entity.ExcelComparison;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class ExcelTaskLifecycleService {
    private static final Set<String> FINAL_STATUSES = Set.of("COMMITTED", "COMPLETED");
    private static final Set<String> ACTIVE_COMPARISON_STATUSES = Set.of("QUEUED", "RUNNING");

    private final ExcelTaskRepository taskRepository;
    private final ExcelTaskSheetRepository sheetRepository;
    private final ExcelTaskRowRepository rowRepository;
    private final ExcelTaskMappingRepository mappingRepository;
    private final ExcelTaskRowResultRepository resultRepository;
    private final ExcelTaskCommitRepository commitRepository;
    private final ExcelTaskOutputRepository outputRepository;
    private final ExcelComparisonRepository comparisonRepository;
    private final ExcelComparisonItemRepository comparisonItemRepository;
    private final AttachmentStorage storage;

    public ExcelTaskLifecycleService(ExcelTaskRepository taskRepository,
                                     ExcelTaskSheetRepository sheetRepository,
                                     ExcelTaskRowRepository rowRepository,
                                     ExcelTaskMappingRepository mappingRepository,
                                     ExcelTaskRowResultRepository resultRepository,
                                     ExcelTaskCommitRepository commitRepository,
                                     ExcelTaskOutputRepository outputRepository,
                                     ExcelComparisonRepository comparisonRepository,
                                     ExcelComparisonItemRepository comparisonItemRepository,
                                     AttachmentStorage storage) {
        this.taskRepository = taskRepository;
        this.sheetRepository = sheetRepository;
        this.rowRepository = rowRepository;
        this.mappingRepository = mappingRepository;
        this.resultRepository = resultRepository;
        this.commitRepository = commitRepository;
        this.outputRepository = outputRepository;
        this.comparisonRepository = comparisonRepository;
        this.comparisonItemRepository = comparisonItemRepository;
        this.storage = storage;
    }

    public void rewindToMapping(Long taskId, Long expectedVersion, Long operatorId) {
        if (expectedVersion == null) throw new IllegalArgumentException("expectedVersion 不能为空");
        ExcelTask task = requireForUpdate(taskId, operatorId);
        if (!expectedVersion.equals(task.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "文件任务已被其他操作更新，请刷新后重试");
        }
        if (!"READY_FOR_REVIEW".equals(task.getStatus())) {
            throw new IllegalArgumentException("只有待复核任务可以返回字段映射");
        }
        task.setStatus("READY_FOR_MAPPING");
        task.setUpdateTime(LocalDateTime.now());
        taskRepository.saveAndFlush(task);
    }

    public void delete(Long taskId, Long operatorId) {
        ExcelTask task = requireForUpdate(taskId, operatorId);
        if (FINAL_STATUSES.contains(task.getStatus())) {
            throw new IllegalArgumentException("已写入业务数据的任务不能删除，请保留作为追溯记录");
        }

        List<ExcelTaskOutput> outputs = outputRepository.findByTaskId(taskId);
        if (outputs.stream().anyMatch(output -> "GENERATING".equals(output.getStatus()))) {
            throw new IllegalArgumentException("报价文件正在生成，完成后再删除任务");
        }
        List<ExcelComparison> comparisons = comparisonRepository.findByBaseTaskIdOrNewTaskId(taskId, taskId);
        if (comparisons.stream().anyMatch(item -> ACTIVE_COMPARISON_STATUSES.contains(item.getStatus()))) {
            throw new IllegalArgumentException("版本比较正在进行，完成或取消后再删除任务");
        }

        comparisons.forEach(item -> comparisonItemRepository.deleteByComparisonId(item.getId()));
        comparisonRepository.deleteAll(comparisons);
        outputRepository.deleteByTaskId(taskId);
        commitRepository.deleteByTaskId(taskId);
        resultRepository.deleteByTaskId(taskId);
        mappingRepository.deleteByTaskId(taskId);
        rowRepository.deleteByTaskId(taskId);
        sheetRepository.deleteByTaskId(taskId);
        taskRepository.delete(task);

        outputs.stream().map(ExcelTaskOutput::getObjectKey).forEach(this::deleteStoredObject);
        if (task.getObjectKey() != null && task.getObjectKey().startsWith("excel-tasks/")) {
            deleteStoredObject(task.getObjectKey());
        }
    }

    private ExcelTask requireForUpdate(Long taskId, Long operatorId) {
        return taskRepository.findForUpdate(taskId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("文件任务不存在或无权访问"));
    }

    private void deleteStoredObject(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        try {
            storage.delete(objectKey);
        } catch (RuntimeException ignored) {
            // 数据库任务删除优先；过期文件仍可由存储清理任务回收。
        }
    }
}
