package com.example.demo.security;

import com.example.demo.entity.AppUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final Duration sessionTtl;
    private final Clock clock;

    public AuthTokenStore() {
        this(Duration.ofHours(8), Clock.systemUTC());
    }

    @Autowired
    public AuthTokenStore(@Value("${auth.token.ttl-minutes:480}") long ttlMinutes) {
        this(Duration.ofMinutes(ttlMinutes), Clock.systemUTC());
    }

    public AuthTokenStore(Duration sessionTtl, Clock clock) {
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("Token 有效期必须大于 0");
        }
        this.sessionTtl = sessionTtl;
        this.clock = java.util.Objects.requireNonNull(clock, "Clock 不能为空");
    }

    public AuthSession createSession(AppUser user) {
        cleanupExpiredSessions();
        String token = UUID.randomUUID().toString().replace("-", "");
        AuthSession session = new AuthSession(token, user, clock.instant().plus(sessionTtl));
        sessions.put(token, session);
        return session;
    }

    public Optional<AuthSession> getSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        AuthSession session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (!session.getExpiresAt().isAfter(clock.instant())) {
            sessions.remove(token, session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void removeSession(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessions.remove(token);
    }

    private void cleanupExpiredSessions() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !entry.getValue().getExpiresAt().isAfter(now));
    }

    int sessionCount() {
        return sessions.size();
    }
}
