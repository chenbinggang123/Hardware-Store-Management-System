package com.example.demo.excel.api;

import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.common.ApiResponse;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.excel.dto.ExcelOutputView;
import com.example.demo.excel.dto.ExcelQuoteRequest;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.service.ExcelQuoteService;
import com.example.demo.security.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/excel-tasks/{taskId}")
public class ExcelOutputController {
    private final ExcelQuoteService service;
    private final AttachmentStorage storage;

    public ExcelOutputController(ExcelQuoteService service, AttachmentStorage storage) {
        this.service = service;
        this.storage = storage;
    }

    @PostMapping("/generate-quote")
    public ResponseEntity<ApiResponse<ExcelOutputView>> generate(
            @PathVariable Long taskId, @RequestBody ExcelQuoteRequest quote,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok("报价文件正在生成", service.generate(taskId, quote, currentUserId(request))));
    }

    @GetMapping("/outputs")
    public ApiResponse<List<ExcelOutputView>> outputs(@PathVariable Long taskId, HttpServletRequest request) {
        return ApiResponse.ok(service.list(taskId, currentUserId(request)));
    }

    @GetMapping("/outputs/{outputId}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable Long taskId, @PathVariable Long outputId, HttpServletRequest request) {
        ExcelTaskOutput output = service.requireReady(taskId, outputId, currentUserId(request));
        StreamingResponseBody body = stream -> {
            try (var input = storage.openStream(output.getObjectKey())) { input.transferTo(stream); }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(output.getOriginalName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CONTENT_TYPE, output.getMimeType())
                .contentLength(output.getFileSize())
                .body(body);
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE);
        if (!(value instanceof AuthSession session) || session.getUser() == null) {
            throw new IllegalArgumentException("无法识别当前操作人");
        }
        return session.getUser().getId();
    }
}
