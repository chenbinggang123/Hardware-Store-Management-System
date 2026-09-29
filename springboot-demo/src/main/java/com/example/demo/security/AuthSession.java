package com.example.demo.security;

import com.example.demo.entity.AppUser;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

/**
 * 登录态快照
 */
@Getter
@AllArgsConstructor
public class AuthSession {
    private final String token;
    private final AppUser user;
    private final Instant expiresAt;

    public AuthSession(String token, AppUser user) {
        this(token, user, null);
    }
}
