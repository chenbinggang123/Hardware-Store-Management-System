package com.example.demo.filecenter;

import com.example.demo.common.ApiResponse;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.filecenter.dto.FileCenterView;
import com.example.demo.security.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/file-center")
public class FileCenterController {
    private final FileCenterService service;

    public FileCenterController(FileCenterService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<FileCenterView> list(@RequestParam(required = false) String state,
                                            @RequestParam(required = false) String keyword,
                                            HttpServletRequest request) {
        return ApiResponse.ok(service.list(currentUserId(request), state, keyword));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE);
        if (!(value instanceof AuthSession session) || session.getUser() == null) {
            throw new IllegalArgumentException("无法识别当前操作人");
        }
        return session.getUser().getId();
    }
}
