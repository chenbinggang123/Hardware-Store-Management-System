-- 2026-10-21 登录令牌持久化
-- 用途：令牌从后端内存改存 MySQL，发布、重启、多实例时不再掉线；默认 30 天有效并在使用中自动续期。
-- 说明：schema.sql 启动时会自动建表；如果生产环境设置了 SPRING_SQL_INIT_MODE=never，请在发布前手动执行本脚本。可重复执行。

CREATE TABLE IF NOT EXISTS auth_token (
    token_hash CHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    KEY idx_auth_token_user (user_id),
    KEY idx_auth_token_expires (expires_at)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
