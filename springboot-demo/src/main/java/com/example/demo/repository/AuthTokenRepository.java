package com.example.demo.repository;

import com.example.demo.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * 登录令牌数据访问接口
 */
public interface AuthTokenRepository extends JpaRepository<AuthToken, String> {

    @Modifying
    @Query("delete from AuthToken t where t.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);

    @Modifying
    @Query("delete from AuthToken t where t.userId = :userId")
    int deleteByUser(@Param("userId") Long userId);
}
