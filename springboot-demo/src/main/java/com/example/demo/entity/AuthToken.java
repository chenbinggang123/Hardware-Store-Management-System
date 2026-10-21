package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * 登录令牌。只保存令牌的 SHA-256，不保存明文，数据库泄露也无法直接冒用。
 */
@Entity
@Table(name = "auth_token")
@Data
public class AuthToken {
    @Id
    @Column(name = "token_hash", length = 64)
    private String tokenHash;
    private Long userId;
    private Instant expiresAt;
    private Instant lastSeenAt;
    private Instant createTime;
}
