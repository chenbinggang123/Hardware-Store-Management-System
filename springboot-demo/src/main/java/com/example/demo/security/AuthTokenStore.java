package com.example.demo.security;

import com.example.demo.entity.AppUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * 登录令牌管理。
 *
 * <p>生产环境令牌存在 MySQL 中，发布和重启后仍然有效；数据库只保存令牌的 SHA-256。
 * 令牌在使用中会自动续期（滑动有效期），默认 30 天不用就过期。</p>
 */
@Component
public class AuthTokenStore {

    static final Duration DEFAULT_TTL = Duration.ofDays(30);
    private static final Duration MAX_RENEW_INTERVAL = Duration.ofDays(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthTokenBackend backend;
    private final Duration sessionTtl;
    private final Duration renewInterval;
    private final Clock clock;

    /** 内存存储，供单元测试使用。 */
    public AuthTokenStore() {
        this(new InMemoryAuthTokenBackend(), DEFAULT_TTL, Clock.systemUTC());
    }

    /** 内存存储，供单元测试使用。 */
    public AuthTokenStore(Duration sessionTtl, Clock clock) {
        this(new InMemoryAuthTokenBackend(), sessionTtl, clock);
    }

    @Autowired
    public AuthTokenStore(JpaAuthTokenBackend backend, @Value("${auth.token.ttl-minutes:43200}") long ttlMinutes) {
        this(backend, Duration.ofMinutes(ttlMinutes), Clock.systemUTC());
    }

    public AuthTokenStore(AuthTokenBackend backend, Duration sessionTtl, Clock clock) {
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("Token 有效期必须大于 0");
        }
        this.backend = Objects.requireNonNull(backend, "令牌存储不能为空");
        this.sessionTtl = sessionTtl;
        Duration half = sessionTtl.dividedBy(2);
        this.renewInterval = half.compareTo(MAX_RENEW_INTERVAL) < 0 ? half : MAX_RENEW_INTERVAL;
        this.clock = Objects.requireNonNull(clock, "Clock 不能为空");
    }

    public AuthSession createSession(AppUser user) {
        Objects.requireNonNull(user, "登录用户不能为空");
        Instant now = clock.instant();
        backend.deleteExpired(now);
        String token = newToken();
        Instant expiresAt = now.plus(sessionTtl);
        backend.save(new AuthTokenBackend.Entry(hash(token), user, expiresAt, now));
        return new AuthSession(token, user, expiresAt);
    }

    public Optional<AuthSession> getSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = hash(token.trim());
        Optional<AuthTokenBackend.Entry> stored = backend.find(tokenHash);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        AuthTokenBackend.Entry entry = stored.get();
        if (!entry.expiresAt().isAfter(now)) {
            backend.delete(tokenHash);
            return Optional.empty();
        }
        Instant lastSeen = entry.lastSeenAt() == null ? Instant.EPOCH : entry.lastSeenAt();
        if (Duration.between(lastSeen, now).compareTo(renewInterval) >= 0) {
            entry = new AuthTokenBackend.Entry(tokenHash, entry.user(), now.plus(sessionTtl), now);
            backend.save(entry);
        }
        return Optional.of(new AuthSession(token.trim(), entry.user(), entry.expiresAt()));
    }

    public void removeSession(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        backend.delete(hash(token.trim()));
    }

    /** 账号被禁用或改密码后，让该用户所有设备重新登录。 */
    public void removeUserSessions(Long userId) {
        if (userId != null) {
            backend.deleteByUser(userId);
        }
    }

    long sessionCount() {
        return backend.count();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", exception);
        }
    }
}
