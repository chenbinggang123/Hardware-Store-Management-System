package com.example.demo.excel.api;

import com.example.demo.common.ApiResponse;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.excel.dto.ExcelComparisonItemPage;
import com.example.demo.excel.dto.ExcelComparisonRequest;
import com.example.demo.excel.dto.ExcelComparisonView;
import com.example.demo.excel.service.ExcelComparisonService;
import com.example.demo.security.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/excel-comparisons")
public class ExcelComparisonController {
    private final ExcelComparisonService service;

    public ExcelComparisonController(ExcelComparisonService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<ExcelComparisonView>> create(
            @RequestBody ExcelComparisonRequest comparison, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok("比较任务已创建", service.create(comparison, currentUserId(request))));
    }

    @GetMapping("/{comparisonId}")
    public ApiResponse<ExcelComparisonView> get(@PathVariable Long comparisonId, HttpServletRequest request) {
        return ApiResponse.ok(service.get(comparisonId, currentUserId(request)));
    }

    @GetMapping("/{comparisonId}/items")
    public ApiResponse<ExcelComparisonItemPage> items(
            @PathVariable Long comparisonId, @RequestParam(required = false) String changeType,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        return ApiResponse.ok(service.items(comparisonId, changeType, page, size, currentUserId(request)));
    }

    @PostMapping("/{comparisonId}/cancel")
    public ApiResponse<ExcelComparisonView> cancel(@PathVariable Long comparisonId, HttpServletRequest request) {
        return ApiResponse.ok("比较任务已取消", service.cancel(comparisonId, currentUserId(request)));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE);
        if (!(value instanceof AuthSession session) || session.getUser() == null) {
            throw new IllegalArgumentException("无法识别当前操作人");
        }
        return session.getUser().getId();
    }
}
