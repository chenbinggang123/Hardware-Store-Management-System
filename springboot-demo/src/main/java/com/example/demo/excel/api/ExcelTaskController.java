package com.example.demo.excel.api;

import com.example.demo.common.ApiResponse;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.excel.dto.ExcelTaskCloudRequest;
import com.example.demo.excel.dto.ExcelTaskDetail;
import com.example.demo.excel.dto.ExcelTaskRowPage;
import com.example.demo.excel.dto.ExcelTaskSummary;
import com.example.demo.excel.dto.ExcelMappingRequest;
import com.example.demo.excel.dto.ExcelReviewRowPage;
import com.example.demo.excel.dto.ExcelReviewSummary;
import com.example.demo.excel.dto.ExcelRowReviewRequest;
import com.example.demo.excel.dto.ExcelTaskCommitRequest;
import com.example.demo.excel.dto.ExcelTaskCommitResult;
import com.example.demo.excel.dto.ExcelTaskValidateRequest;
import com.example.demo.excel.dto.ExcelTaskValidation;
import com.example.demo.excel.service.ExcelTaskCommitService;
import com.example.demo.excel.service.ExcelTaskMappingService;
import com.example.demo.excel.service.ExcelTaskService;
import com.example.demo.security.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/excel-tasks")
public class ExcelTaskController {
    private final ExcelTaskService service;
    private final ExcelTaskMappingService mappingService;
    private final ExcelTaskCommitService commitService;

    public ExcelTaskController(ExcelTaskService service, ExcelTaskMappingService mappingService,
                               ExcelTaskCommitService commitService) {
        this.service = service;
        this.mappingService = mappingService;
        this.commitService = commitService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ExcelTaskDetail> upload(@RequestPart("file") MultipartFile file,
                                               @RequestParam(required = false) String purpose,
                                               HttpServletRequest request) {
        return ApiResponse.ok("文件任务创建完成", service.upload(file, purpose, currentUserId(request)));
    }

    @PostMapping("/cloud")
    public ApiResponse<ExcelTaskDetail> uploadCloud(@RequestBody ExcelTaskCloudRequest cloudRequest,
                                                    HttpServletRequest request) {
        return ApiResponse.ok("文件任务创建完成", service.uploadCloud(cloudRequest, currentUserId(request)));
    }

    @GetMapping
    public ApiResponse<List<ExcelTaskSummary>> list(@RequestParam(required = false) String status,
                                                    HttpServletRequest request) {
        return ApiResponse.ok(service.list(currentUserId(request), status));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<ExcelTaskDetail> get(@PathVariable Long taskId, HttpServletRequest request) {
        return ApiResponse.ok(service.get(taskId, currentUserId(request)));
    }

    @GetMapping("/{taskId}/sheets/{sheetId}/rows")
    public ApiResponse<ExcelTaskRowPage> rows(@PathVariable Long taskId,
                                             @PathVariable Long sheetId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size,
                                             HttpServletRequest request) {
        return ApiResponse.ok(service.rows(taskId, sheetId, page, size, currentUserId(request)));
    }

    @PutMapping("/{taskId}/sheets/{sheetId}/mapping")
    public ApiResponse<ExcelReviewSummary> applyMapping(@PathVariable Long taskId,
                                                        @PathVariable Long sheetId,
                                                        @RequestBody ExcelMappingRequest mapping,
                                                        HttpServletRequest request) {
        return ApiResponse.ok("字段映射已保存，变更预览已生成",
                mappingService.apply(taskId, sheetId, mapping, currentUserId(request)));
    }

    @GetMapping("/{taskId}/sheets/{sheetId}/review-summary")
    public ApiResponse<ExcelReviewSummary> reviewSummary(@PathVariable Long taskId,
                                                         @PathVariable Long sheetId,
                                                         HttpServletRequest request) {
        return ApiResponse.ok(mappingService.summary(taskId, sheetId, currentUserId(request)));
    }

    @GetMapping("/{taskId}/sheets/{sheetId}/review-rows")
    public ApiResponse<ExcelReviewRowPage> reviewRows(@PathVariable Long taskId,
                                                      @PathVariable Long sheetId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(mappingService.rows(taskId, sheetId, page, size, currentUserId(request)));
    }

    @PutMapping("/{taskId}/sheets/{sheetId}/review-rows/{resultId}")
    public ApiResponse<Void> reviewRow(@PathVariable Long taskId,
                                       @PathVariable Long sheetId,
                                       @PathVariable Long resultId,
                                       @RequestBody ExcelRowReviewRequest review,
                                       HttpServletRequest request) {
        commitService.reviewRow(taskId, sheetId, resultId, review, currentUserId(request));
        return ApiResponse.ok("审核结果已保存", null);
    }

    @PostMapping("/{taskId}/validate")
    public ApiResponse<ExcelTaskValidation> validate(@PathVariable Long taskId,
                                                     @RequestBody ExcelTaskValidateRequest validation,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(commitService.validate(taskId,
                validation == null ? null : validation.expectedVersion(), currentUserId(request)));
    }

    @PostMapping("/{taskId}/commit")
    public ApiResponse<ExcelTaskCommitResult> commit(@PathVariable Long taskId,
                                                     @RequestBody ExcelTaskCommitRequest commit,
                                                     HttpServletRequest request) {
        return ApiResponse.ok("文件任务提交完成",
                commitService.commit(taskId, commit, currentUserId(request)));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE);
        if (!(value instanceof AuthSession session) || session.getUser() == null) {
            throw new IllegalArgumentException("无法识别当前操作人");
        }
        return session.getUser().getId();
    }
}
