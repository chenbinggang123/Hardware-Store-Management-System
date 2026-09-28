package com.example.demo.security;

import com.example.demo.entity.AppUser;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 简单内存 Token 存储
 */
@Component
public class AuthTokenStore {

    private final ConcurrentMap<String, AuthSession> sessions = new ConcurrentHashMap<>();

    public AuthSession createSession(AppUser user) {
        String token = UUID.randomUUID().toString().replace("-", "");
        AuthSession session = new AuthSession(token, user);
        sessions.put(token, session);
        return session;
    }

    public Optional<AuthSession> getSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(token));
    }

    public void removeSession(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessions.remove(token);
    }
}
