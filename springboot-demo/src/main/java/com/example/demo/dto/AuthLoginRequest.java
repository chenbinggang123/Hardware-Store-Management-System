package com.example.demo.dto;

import lombok.Data;

/**
 * 登录请求
 */
@Data
public class AuthLoginRequest {
    private String username;
    private String password;
}
