-- 价格历史、报价输出、Excel 版本比较升级脚本（MySQL 8）
-- 适用于已经存在 excel_task 的环境；首次初始化环境直接使用 springboot-demo/src/main/resources/schema.sql。
-- 本脚本只执行一次。执行前请备份数据库，并先确认 excel_task 尚无 supplier_id、price_effective_date 两列。

ALTER TABLE excel_task
    ADD COLUMN supplier_id BIGINT NULL AFTER operator_id,
    ADD COLUMN price_effective_date DATE NULL AFTER supplier_id;

CREATE TABLE IF NOT EXISTS product_price_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    supplier_id BIGINT,
    price_type VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    before_price DECIMAL(12,2),
    after_price DECIMAL(12,2),
    change_percent DECIMAL(9,4),
    applied_to_product TINYINT NOT NULL DEFAULT 1,
    source_type VARCHAR(32) NOT NULL,
    source_task_id BIGINT,
    source_record_id BIGINT,
    source_line_key VARCHAR(160),
    operator_id BIGINT NOT NULL,
    effective_date DATE,
    reason VARCHAR(255),
    idempotency_key VARCHAR(160) NOT NULL,
    create_time DATETIME NOT NULL,
    UNIQUE KEY uk_price_history_idempotency (idempotency_key),
    KEY idx_price_history_product_time (product_id, create_time),
    KEY idx_price_history_supplier_time (supplier_id, create_time),
    KEY idx_price_history_source (source_type, source_record_id)
);

CREATE TABLE IF NOT EXISTS excel_task_output (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    output_type VARCHAR(32) NOT NULL,
    object_key VARCHAR(500),
    original_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    sha256 VARCHAR(64) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    summary_json LONGTEXT,
    error_message VARCHAR(1000),
    create_time DATETIME NOT NULL,
    expires_at DATETIME,
    UNIQUE KEY uk_excel_output_idempotency (operator_id, idempotency_key),
    KEY idx_excel_output_task_time (task_id, create_time)
);

CREATE TABLE IF NOT EXISTS excel_comparison (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT NOT NULL,
    base_task_id BIGINT NOT NULL,
    base_task_version BIGINT NOT NULL,
    new_task_id BIGINT NOT NULL,
    new_task_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    match_keys_json VARCHAR(500) NOT NULL,
    compare_fields_json VARCHAR(1000) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    summary_json LONGTEXT,
    progress_completed INT,
    progress_total INT,
    issue_count INT NOT NULL DEFAULT 0,
    cancel_requested_at DATETIME,
    error_code VARCHAR(64),
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    UNIQUE KEY uk_excel_comparison_idempotency (operator_id, idempotency_key),
    KEY idx_excel_comparison_operator_time (operator_id, create_time)
);

CREATE TABLE IF NOT EXISTS excel_comparison_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    comparison_id BIGINT NOT NULL,
    change_type VARCHAR(32) NOT NULL,
    match_key VARCHAR(500),
    product_id BIGINT,
    product_name VARCHAR(255),
    base_row_number INT,
    new_row_number INT,
    changes_json LONGTEXT NOT NULL,
    issue_code VARCHAR(64),
    issue_message VARCHAR(500),
    KEY idx_excel_comparison_item_page (comparison_id, change_type, id)
);
