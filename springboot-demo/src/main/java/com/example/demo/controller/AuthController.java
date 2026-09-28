package com.example.demo.controller;

import com.example.demo.common.ApiResponse;
import com.example.demo.dto.AuthLoginRequest;
import com.example.demo.dto.AuthSessionResponse;
import com.example.demo.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录认证接口
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthSessionResponse> login(@RequestBody AuthLoginRequest request) {
        return ApiResponse.ok("登录成功", authService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<AuthSessionResponse> currentUser(HttpServletRequest request) {
        return ApiResponse.ok("当前登录用户查询成功", authService.getCurrentUser(extractToken(request)));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        authService.logout(resolveToken(authorization, request));
        return ApiResponse.ok("退出登录成功", Map.of("success", true));
    }

    private String extractToken(HttpServletRequest request) {
        return resolveToken(request.getHeader("Authorization"), request);
    }

    private String resolveToken(String authorization, HttpServletRequest request) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        String token = request.getHeader("X-Token");
        return token == null ? "" : token.trim();
    }
}
