package com.example.demo.dto;

import lombok.Data;

import java.time.Instant;

/**
 * 登录会话返回
 */
@Data
public class AuthSessionResponse {
    private String token;
    private Long id;
    private String username;
    private String name;
    private String role;
    private Integer status;
    private Instant expiresAt;
}
