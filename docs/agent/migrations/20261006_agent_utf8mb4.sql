-- 修复 Agent 文本写入 Emoji 时的 Incorrect string value（MySQL 8）
-- 执行前请备份数据库。本脚本可重复执行。

ALTER TABLE agent_conversation
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

ALTER TABLE agent_run
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

ALTER TABLE agent_attachment
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

ALTER TABLE agent_tool_call
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
