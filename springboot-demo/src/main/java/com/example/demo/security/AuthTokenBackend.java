package com.example.demo.security;

import com.example.demo.entity.AppUser;

import java.time.Instant;
import java.util.Optional;

/**
 * 令牌的存储位置。生产环境存 MySQL（{@link JpaAuthTokenBackend}），单元测试用内存实现。
 * 所有方法只接收令牌哈希，从不接触令牌明文。
 */
public interface AuthTokenBackend {

    record Entry(String tokenHash, AppUser user, Instant expiresAt, Instant lastSeenAt) {
    }

    void save(Entry entry);

    Optional<Entry> find(String tokenHash);

    void delete(String tokenHash);

    void deleteByUser(Long userId);

    void deleteExpired(Instant now);

    long count();
}
