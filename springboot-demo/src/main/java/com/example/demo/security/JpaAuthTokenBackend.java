package com.example.demo.security;

import com.example.demo.entity.AuthToken;
import com.example.demo.repository.AppUserRepository;
import com.example.demo.repository.AuthTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * MySQL 令牌存储：服务发布、重启或多实例时登录状态都不会丢失。
 */
@Component
@Transactional
public class JpaAuthTokenBackend implements AuthTokenBackend {

    private final AuthTokenRepository tokens;
    private final AppUserRepository users;

    public JpaAuthTokenBackend(AuthTokenRepository tokens, AppUserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    public void save(Entry entry) {
        AuthToken token = tokens.findById(entry.tokenHash()).orElseGet(() -> {
            AuthToken created = new AuthToken();
            created.setTokenHash(entry.tokenHash());
            created.setCreateTime(entry.lastSeenAt());
            return created;
        });
        token.setUserId(entry.user().getId());
        token.setExpiresAt(entry.expiresAt());
        token.setLastSeenAt(entry.lastSeenAt());
        tokens.save(token);
    }

    @Override
    public Optional<Entry> find(String tokenHash) {
        Optional<AuthToken> stored = tokens.findById(tokenHash);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        AuthToken token = stored.get();
        return users.findById(token.getUserId())
                .map(user -> new Entry(tokenHash, user, token.getExpiresAt(), token.getLastSeenAt()))
                .or(() -> {
                    tokens.deleteById(tokenHash);
                    return Optional.empty();
                });
    }

    @Override
    public void delete(String tokenHash) {
        tokens.deleteById(tokenHash);
    }

    @Override
    public void deleteByUser(Long userId) {
        if (userId != null) {
            tokens.deleteByUser(userId);
        }
    }

    @Override
    public void deleteExpired(Instant now) {
        tokens.deleteExpired(now);
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return tokens.count();
    }
}
