package com.example.demo.agent.service;

import com.example.demo.agent.dto.AgentFileView;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.entity.OperationLog;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.repository.OperationLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Files the agent generated for a user: listing, download checks and user-driven deletion.
 * Agent files never expire; the owner removes them explicitly from “我的文件” or the conversation.
 */
@Service
public class AgentOutputService {
    public static final String AGENT_SOURCE_TYPE = "AGENT_WORKBOOK";
    private static final Logger log = LoggerFactory.getLogger(AgentOutputService.class);

    private final ExcelTaskOutputRepository outputRepository;
    private final AttachmentStorage storage;
    private final OperationLogRepository operationLogRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentOutputService(ExcelTaskOutputRepository outputRepository,
                              AttachmentStorage storage,
                              OperationLogRepository operationLogRepository,
                              ObjectMapper objectMapper) {
        this.outputRepository = outputRepository;
        this.storage = storage;
        this.operationLogRepository = operationLogRepository;
        this.objectMapper = objectMapper;
    }

    public AgentOutputService(ExcelTaskOutputRepository outputRepository, AttachmentStorage storage, ObjectMapper objectMapper) {
        this(outputRepository, storage, null, objectMapper);
    }

    @Transactional(readOnly = true)
    public List<AgentFileView> listMine(Long operatorId) {
        if (operatorId == null) throw new IllegalArgumentException("缺少操作人");
        return outputRepository.findByOperatorIdAndSourceTypeOrderByCreateTimeDesc(operatorId, AGENT_SOURCE_TYPE)
                .stream().map(this::view).toList();
    }

    /** Files attached to one run; callers that already verified the run owner use this directly. */
    @Transactional(readOnly = true)
    public List<AgentFileView> forRun(Long runId) {
        if (runId == null) return List.of();
        return outputRepository.findByRunIdOrderByCreateTimeAsc(runId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<AgentFileView> forRun(Long runId, Long operatorId) {
        if (runId == null || operatorId == null) return List.of();
        return outputRepository.findByRunIdOrderByCreateTimeAsc(runId).stream()
                .filter(item -> operatorId.equals(item.getOperatorId()))
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public AgentFileView get(Long outputId, Long operatorId) {
        return view(require(outputId, operatorId));
    }

    @Transactional(readOnly = true)
    public ExcelTaskOutput requireReady(Long outputId, Long operatorId) {
        ExcelTaskOutput output = require(outputId, operatorId);
        if (output.getExpiresAt() != null && !output.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("文件已过期");
        }
        if (!"READY".equals(output.getStatus())) throw new IllegalArgumentException("文件尚未生成完成或生成失败");
        if (!StringUtils.hasText(output.getObjectKey())) throw new IllegalArgumentException("文件内容不存在");
        return output;
    }

    @Transactional
    public void delete(Long outputId, Long operatorId) {
        ExcelTaskOutput output = require(outputId, operatorId);
        if (!AGENT_SOURCE_TYPE.equals(output.getSourceType())) {
            throw new IllegalArgumentException("只能删除小五生成的文件；文件任务的输出请在对应文件任务中删除");
        }
        if (StringUtils.hasText(output.getObjectKey())) {
            try {
                storage.delete(output.getObjectKey());
            } catch (RuntimeException exception) {
                // The user asked for the file to go away; a storage hiccup must not keep a dead record alive.
                log.warn("删除小五生成文件对象失败，仍移除记录 outputId={} key={}", output.getId(), output.getObjectKey(), exception);
            }
        }
        outputRepository.delete(output);
        audit(operatorId, "DELETE", "删除小五生成的文件：" + output.getOriginalName() + "（#" + output.getId() + "）");
    }

    public AgentFileView view(ExcelTaskOutput output) {
        return AgentFileView.from(output, objectMapper);
    }

    private ExcelTaskOutput require(Long outputId, Long operatorId) {
        if (outputId == null || operatorId == null) throw new IllegalArgumentException("文件不存在或无权访问");
        return outputRepository.findByIdAndOperatorId(outputId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在或无权访问"));
    }

    private void audit(Long operatorId, String action, String detail) {
        if (operationLogRepository == null) return;
        OperationLog entry = new OperationLog();
        entry.setOperatorId(operatorId);
        entry.setModule("AGENT_FILE");
        entry.setAction(action);
        entry.setDetail(detail.length() <= 255 ? detail : detail.substring(0, 255));
        entry.setCreateTime(LocalDateTime.now());
        operationLogRepository.save(entry);
    }
}
