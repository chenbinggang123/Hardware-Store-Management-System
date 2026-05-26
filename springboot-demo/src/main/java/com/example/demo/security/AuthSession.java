package com.example.demo.security;

import com.example.demo.entity.AppUser;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 登录态快照
 */
@Getter
@AllArgsConstructor
public class AuthSession {
    private final String token;
    private final AppUser user;
}
