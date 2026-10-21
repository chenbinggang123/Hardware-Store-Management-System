package com.example.demo.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存令牌存储，仅用于单元测试和无数据库的场景；进程重启后令牌全部失效。
 */
public class InMemoryAuthTokenBackend implements AuthTokenBackend {

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void save(Entry entry) {
        entries.put(entry.tokenHash(), entry);
    }

    @Override
    public Optional<Entry> find(String tokenHash) {
        return Optional.ofNullable(entries.get(tokenHash));
    }

    @Override
    public void delete(String tokenHash) {
        entries.remove(tokenHash);
    }

    @Override
    public void deleteByUser(Long userId) {
        entries.values().removeIf(entry -> entry.user() != null && Objects.equals(entry.user().getId(), userId));
    }

    @Override
    public void deleteExpired(Instant now) {
        entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
    }

    @Override
    public long count() {
        return entries.size();
    }
}
