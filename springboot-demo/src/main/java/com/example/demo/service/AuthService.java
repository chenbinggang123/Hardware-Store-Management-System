package com.example.demo.service;

import com.example.demo.dto.AuthLoginRequest;
import com.example.demo.dto.AuthSessionResponse;

/**
 * 登录认证服务
 */
public interface AuthService {
    AuthSessionResponse login(AuthLoginRequest request);
    AuthSessionResponse getCurrentUser(String token);
    void logout(String token);
}
