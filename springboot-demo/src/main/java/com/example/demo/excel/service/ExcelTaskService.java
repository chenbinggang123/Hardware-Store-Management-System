package com.example.demo.excel.service;

import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.agent.service.attachment.CloudBaseFileDownloader;
import com.example.demo.excel.dto.ExcelTaskCloudRequest;
import com.example.demo.excel.dto.ExcelTaskDetail;
import com.example.demo.excel.dto.ExcelTaskRowPage;
import com.example.demo.excel.dto.ExcelTaskRowView;
import com.example.demo.excel.dto.ExcelTaskSheetView;
import com.example.demo.excel.dto.ExcelTaskSummary;
import com.example.demo.excel.entity.ExcelTask;
import com.example.demo.excel.entity.ExcelTaskRow;
import com.example.demo.excel.entity.ExcelTaskSheet;
import com.example.demo.excel.parser.ExcelTaskParser;
import com.example.demo.excel.parser.ParsedWorkbook;
import com.example.demo.excel.repository.ExcelTaskRepository;
import com.example.demo.excel.repository.ExcelTaskRowRepository;
import com.example.demo.excel.repository.ExcelTaskSheetRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class ExcelTaskService {
    private static final Set<String> PURPOSES = Set.of("BUYER_QUOTE", "SUPPLIER_PRICE", "PRODUCT_IMPORT",
            "INVENTORY_COUNT", "PURCHASE_RECEIPT", "UNCLASSIFIED");
    private static final Set<String> EXTENSIONS = Set.of("xls", "xlsx", "csv");

    private final ExcelTaskRepository taskRepository;
    private final ExcelTaskSheetRepository sheetRepository;
    private final ExcelTaskRowRepository rowRepository;
    private final ExcelTaskParser parser;
    private final AttachmentStorage storage;
    private final CloudBaseFileDownloader cloudFileDownloader;
    private final ObjectMapper objectMapper;
    private final long maxSize;

    public ExcelTaskService(ExcelTaskRepository taskRepository,
                            ExcelTaskSheetRepository sheetRepository,
                            ExcelTaskRowRepository rowRepository,
                            ExcelTaskParser parser,
                            AttachmentStorage storage,
                            CloudBaseFileDownloader cloudFileDownloader,
                            ObjectMapper objectMapper,
                            @Value("${excel.task.max-size-bytes:10485760}") long maxSize) {
        this.taskRepository = taskRepository;
        this.sheetRepository = sheetRepository;
        this.rowRepository = rowRepository;
        this.parser = parser;
        this.storage = storage;
        this.cloudFileDownloader = cloudFileDownloader;
        this.objectMapper = objectMapper;
        this.maxSize = maxSize;
    }

    public ExcelTaskDetail upload(MultipartFile file, String purpose, Long operatorId) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要上传的表格");
        if (file.getSize() > maxSize) throw tooLarge();
        String originalName = cleanName(file.getOriginalFilename());
        String extension = validateExtension(originalName);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取上传文件", exception);
        }
        String mimeType = detectedMime(extension, content);
        String objectKey = "excel-tasks/" + operatorId + "/" + LocalDate.now() + "/" + UUID.randomUUID() + "." + extension;
        storage.put(objectKey, content, mimeType);
        return create(operatorId, purpose, objectKey, originalName, mimeType, content);
    }

    public ExcelTaskDetail uploadCloud(ExcelTaskCloudRequest request, Long operatorId) {
        if (request == null) throw new IllegalArgumentException("文件信息不能为空");
        if (request.getFileSize() != null && request.getFileSize() > maxSize) throw tooLarge();
        String originalName = cleanName(request.getOriginalName());
        String extension = validateExtension(originalName);
        byte[] content = cloudFileDownloader.download(request.getCloudFileId(), request.getDownloadUrl(), maxSize);
        String mimeType = detectedMime(extension, content);
        return create(operatorId, request.getPurpose(), request.getCloudFileId(), originalName, mimeType, content);
    }

    private ExcelTaskDetail create(Long operatorId, String purpose, String objectKey,
                                   String originalName, String mimeType, byte[] content) {
        LocalDateTime now = LocalDateTime.now();
        ExcelTask task = new ExcelTask();
        task.setOperatorId(operatorId);
        task.setPurpose(normalizePurpose(purpose));
        task.setStatus("PARSING");
        task.setOriginalName(originalName);
        task.setObjectKey(objectKey);
        task.setMimeType(mimeType);
        task.setFileSize((long) content.length);
        task.setSha256(sha256(content));
        task.setSheetCount(0);
        task.setRowCount(0);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task = taskRepository.save(task);
        try {
            ParsedWorkbook workbook = parser.parse(mimeType, content);
            int dataRows = 0;
            for (ParsedWorkbook.ParsedSheet parsedSheet : workbook.sheets()) {
                ExcelTaskSheet sheet = new ExcelTaskSheet();
                sheet.setTaskId(task.getId());
                sheet.setSheetIndex(parsedSheet.index());
                sheet.setSheetName(parsedSheet.name());
                sheet.setHeaderRowNumber(parsedSheet.headerRowNumber());
                sheet.setDataStartRowNumber(parsedSheet.dataStartRowNumber());
                sheet.setRowCount(parsedSheet.rows().size());
                sheet.setColumnCount(parsedSheet.columns().size());
                sheet.setColumnsJson(write(parsedSheet.columns()));
                sheet = sheetRepository.save(sheet);
                List<ExcelTaskRow> rows = new ArrayList<>();
                for (ParsedWorkbook.ParsedRow parsedRow : parsedSheet.rows()) {
                    ExcelTaskRow row = new ExcelTaskRow();
                    row.setTaskId(task.getId());
                    row.setSheetId(sheet.getId());
                    row.setRowNumber(parsedRow.rowNumber());
                    row.setReviewStatus("UNREVIEWED");
                    row.setCellsJson(write(parsedRow.cells()));
                    rows.add(row);
                }
                rowRepository.saveAll(rows);
                dataRows += rows.size();
            }
            task.setSheetCount(workbook.sheets().size());
            task.setRowCount(dataRows);
            task.setStatus("READY_FOR_MAPPING");
        } catch (RuntimeException exception) {
            task.setStatus("FAILED");
            task.setErrorMessage(limit(exception.getMessage(), 1000));
        }
        task.setUpdateTime(LocalDateTime.now());
        return detail(taskRepository.saveAndFlush(task));
    }

    @Transactional(readOnly = true)
    public List<ExcelTaskSummary> list(Long operatorId, String status) {
        List<ExcelTask> tasks = StringUtils.hasText(status)
                ? taskRepository.findByOperatorIdAndStatusOrderByUpdateTimeDesc(operatorId, status.trim().toUpperCase(Locale.ROOT))
                : taskRepository.findByOperatorIdOrderByUpdateTimeDesc(operatorId);
        return tasks.stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public ExcelTaskDetail get(Long taskId, Long operatorId) {
        return detail(requireTask(taskId, operatorId));
    }

    @Transactional(readOnly = true)
    public ExcelTaskRowPage rows(Long taskId, Long sheetId, int page, int size, Long operatorId) {
        requireTask(taskId, operatorId);
        sheetRepository.findByIdAndTaskId(sheetId, taskId)
                .orElseThrow(() -> new IllegalArgumentException("工作表不存在"));
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        var result = rowRepository.findByTaskIdAndSheetIdOrderByRowNumberAsc(
                taskId, sheetId, PageRequest.of(safePage, safeSize));
        List<ExcelTaskRowView> items = result.getContent().stream()
                .map(row -> new ExcelTaskRowView(row.getId(), row.getRowNumber(), row.getReviewStatus(), read(row.getCellsJson())))
                .toList();
        return new ExcelTaskRowPage(taskId, sheetId, safePage, safeSize,
                result.getTotalElements(), result.getTotalPages(), items);
    }

    private ExcelTask requireTask(Long taskId, Long operatorId) {
        return taskRepository.findByIdAndOperatorId(taskId, operatorId)
                .orElseThrow(() -> new IllegalArgumentException("文件任务不存在或无权访问"));
    }

    private ExcelTaskDetail detail(ExcelTask task) {
        List<ExcelTaskSheetView> sheets = sheetRepository.findByTaskIdOrderBySheetIndexAsc(task.getId()).stream()
                .map(sheet -> new ExcelTaskSheetView(sheet.getId(), sheet.getSheetIndex(), sheet.getSheetName(),
                        sheet.getHeaderRowNumber(), sheet.getDataStartRowNumber(), sheet.getRowCount(),
                        sheet.getColumnCount(), read(sheet.getColumnsJson())))
                .toList();
        return new ExcelTaskDetail(task.getId(), task.getOriginalName(), task.getPurpose(), task.getStatus(),
                task.getMimeType(), task.getFileSize(), task.getSha256(), task.getSheetCount(), task.getRowCount(),
                task.getErrorMessage(), task.getCreateTime(), task.getUpdateTime(), task.getVersion(), sheets);
    }

    private ExcelTaskSummary summary(ExcelTask task) {
        return new ExcelTaskSummary(task.getId(), task.getOriginalName(), task.getPurpose(), task.getStatus(),
                task.getSheetCount(), task.getRowCount(), task.getErrorMessage(), task.getCreateTime(), task.getUpdateTime());
    }

    private String write(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("表格数据序列化失败", exception);
        }
    }

    private List<String> read(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("表格数据读取失败", exception);
        }
    }

    private String normalizePurpose(String value) {
        String purpose = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "UNCLASSIFIED";
        if (!PURPOSES.contains(purpose)) throw new IllegalArgumentException("不支持的文件用途：" + value);
        return purpose;
    }

    private String cleanName(String value) {
        String normalized = StringUtils.hasText(value) ? value.replace('\\', '/') : "";
        String name = StringUtils.hasText(normalized) ? normalized.substring(normalized.lastIndexOf('/') + 1) : "spreadsheet";
        return name.length() <= 255 ? name : name.substring(name.length() - 255);
    }

    private String validateExtension(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) throw new IllegalArgumentException("文件任务仅支持 XLS、XLSX 和 CSV");
        return extension;
    }

    private String detectedMime(String extension, byte[] bytes) {
        if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K') {
            if (!"xlsx".equals(extension)) throw mismatch();
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (bytes.length >= 4 && bytes[0] == (byte) 0xd0 && bytes[1] == (byte) 0xcf
                && bytes[2] == 0x11 && bytes[3] == (byte) 0xe0) {
            if (!"xls".equals(extension)) throw mismatch();
            return "application/vnd.ms-excel";
        }
        if ("csv".equals(extension) && java.util.stream.IntStream.range(0, Math.min(bytes.length, 4096))
                .noneMatch(index -> bytes[index] == 0)) return "text/csv";
        throw mismatch();
    }

    private IllegalArgumentException mismatch() {
        return new IllegalArgumentException("文件内容与扩展名不匹配或格式不受支持");
    }

    private IllegalArgumentException tooLarge() {
        return new IllegalArgumentException("单个表格不能超过 " + maxSize / 1024 / 1024 + "MB");
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前环境不支持 SHA-256", exception);
        }
    }

    private String limit(String value, int max) {
        if (!StringUtils.hasText(value)) return "未知解析错误";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
