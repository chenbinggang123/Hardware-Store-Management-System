CREATE TABLE IF NOT EXISTS product (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    barcode VARCHAR(64) NOT NULL,
    spec VARCHAR(100),
    unit VARCHAR(32),
    unit_convert VARCHAR(255),
    retail_price DECIMAL(10, 2) DEFAULT 0.00,
    wholesale_price DECIMAL(10, 2) DEFAULT 0.00,
    old_customer_price DECIMAL(10, 2) DEFAULT 0.00,
    cost_price DECIMAL(10, 2) DEFAULT 0.00,
    stock INT DEFAULT 0,
    location_id VARCHAR(64),
    source_factory VARCHAR(100),
    image_url VARCHAR(255),
    status INT DEFAULT 1,
    create_time DATETIME NOT NULL,
    UNIQUE KEY uk_product_barcode (barcode)
);

CREATE TABLE IF NOT EXISTS supplier (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    contact VARCHAR(50),
    phone VARCHAR(30),
    address VARCHAR(255),
    remark VARCHAR(255),
    create_time DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS customer (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(32),
    phone VARCHAR(30),
    address VARCHAR(255),
    debt DECIMAL(10, 2) DEFAULT 0.00,
    remark VARCHAR(255),
    create_time DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS purchase_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_number VARCHAR(64) NOT NULL,
    supplier_id BIGINT NOT NULL,
    operator_id BIGINT,
    order_time DATETIME NOT NULL,
    total_amount DECIMAL(10, 2) DEFAULT 0.00,
    status VARCHAR(32),
    remark VARCHAR(255),
    create_time DATETIME NOT NULL,
    items_json LONGTEXT,
    UNIQUE KEY uk_purchase_order_number (order_number)
);

CREATE TABLE IF NOT EXISTS sales_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_number VARCHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    operator_id BIGINT,
    order_time DATETIME NOT NULL,
    total_amount DECIMAL(10, 2) DEFAULT 0.00,
    status VARCHAR(32),
    pay_status VARCHAR(32),
    received_amount DECIMAL(10, 2) DEFAULT 0.00,
    debt_amount DECIMAL(10, 2) DEFAULT 0.00,
    create_time DATETIME NOT NULL,
    items_json LONGTEXT,
    UNIQUE KEY uk_sales_order_number (order_number)
);

CREATE TABLE IF NOT EXISTS sales_order_draft (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    total_amount DECIMAL(10, 2) DEFAULT 0.00,
    received_amount DECIMAL(10, 2) DEFAULT 0.00,
    debt_amount DECIMAL(10, 2) DEFAULT 0.00,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME NOT NULL,
    committed_order_id BIGINT,
    commit_idempotency_key VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    items_json LONGTEXT,
    UNIQUE KEY uk_sales_draft_commit_key (commit_idempotency_key),
    KEY idx_sales_draft_operator_status (operator_id, status)
);

CREATE TABLE IF NOT EXISTS agent_conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    KEY idx_agent_conversation_operator_update (operator_id, update_time)
);

CREATE TABLE IF NOT EXISTS agent_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_step INT NOT NULL DEFAULT 0,
    input_text LONGTEXT,
    output_text LONGTEXT,
    context_json LONGTEXT,
    pending_tool_call_id VARCHAR(100),
    pending_tool_name VARCHAR(64),
    pending_arguments_json LONGTEXT,
    pending_preview_json LONGTEXT,
    error_code VARCHAR(100),
    started_at DATETIME NOT NULL,
    completed_at DATETIME,
    expires_at DATETIME NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    KEY idx_agent_run_operator_status (operator_id, status)
);

CREATE TABLE IF NOT EXISTS agent_conversation_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    create_time DATETIME NOT NULL,
    UNIQUE KEY uk_agent_conversation_run (run_id),
    KEY idx_agent_conversation_run_order (conversation_id, create_time)
);

CREATE TABLE IF NOT EXISTS agent_attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    run_id BIGINT,
    operator_id BIGINT NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    parse_status VARCHAR(32) NOT NULL,
    extracted_text LONGTEXT,
    error_message VARCHAR(1000),
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    KEY idx_agent_attachment_conversation (conversation_id, create_time),
    KEY idx_agent_attachment_run (run_id),
    KEY idx_agent_attachment_operator (operator_id)
);

CREATE TABLE IF NOT EXISTS agent_tool_call (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    tool_call_id VARCHAR(100),
    tool_name VARCHAR(64) NOT NULL,
    arguments_json LONGTEXT,
    result_json LONGTEXT,
    status VARCHAR(32) NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    KEY idx_agent_tool_call_run_id (run_id)
);

CREATE TABLE IF NOT EXISTS excel_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT NOT NULL,
    supplier_id BIGINT,
    price_effective_date DATE,
    purpose VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    sheet_count INT NOT NULL DEFAULT 0,
    row_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    KEY idx_excel_task_operator_update (operator_id, update_time),
    KEY idx_excel_task_operator_status (operator_id, status)
);

-- 兼容已经存在的 excel_task；MySQL 不支持 ADD COLUMN IF NOT EXISTS，使用元数据判断后执行。
SET @add_excel_task_supplier = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task ADD COLUMN supplier_id BIGINT NULL AFTER operator_id',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'excel_task' AND column_name = 'supplier_id'
);
PREPARE add_excel_task_supplier_stmt FROM @add_excel_task_supplier;
EXECUTE add_excel_task_supplier_stmt;
DEALLOCATE PREPARE add_excel_task_supplier_stmt;

SET @add_excel_task_effective_date = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task ADD COLUMN price_effective_date DATE NULL AFTER supplier_id',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'excel_task' AND column_name = 'price_effective_date'
);
PREPARE add_excel_task_effective_date_stmt FROM @add_excel_task_effective_date;
EXECUTE add_excel_task_effective_date_stmt;
DEALLOCATE PREPARE add_excel_task_effective_date_stmt;

CREATE TABLE IF NOT EXISTS excel_task_sheet (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    sheet_index INT NOT NULL,
    sheet_name VARCHAR(100) NOT NULL,
    header_row_number INT NOT NULL,
    data_start_row_number INT NOT NULL,
    row_count INT NOT NULL DEFAULT 0,
    column_count INT NOT NULL DEFAULT 0,
    columns_json LONGTEXT NOT NULL,
    UNIQUE KEY uk_excel_task_sheet_index (task_id, sheet_index),
    KEY idx_excel_task_sheet_task (task_id)
);

CREATE TABLE IF NOT EXISTS excel_task_row (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    sheet_id BIGINT NOT NULL,
    source_row_number INT NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    cells_json LONGTEXT NOT NULL,
    UNIQUE KEY uk_excel_task_row_number (sheet_id, source_row_number),
    KEY idx_excel_task_row_task_sheet (task_id, sheet_id, source_row_number)
);

CREATE TABLE IF NOT EXISTS excel_task_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    sheet_id BIGINT NOT NULL,
    source_column_index INT NOT NULL,
    source_column_name VARCHAR(255) NOT NULL,
    target_field VARCHAR(32) NOT NULL,
    create_time DATETIME NOT NULL,
    UNIQUE KEY uk_excel_task_mapping_source (sheet_id, source_column_index),
    KEY idx_excel_task_mapping_task_sheet (task_id, sheet_id)
);

CREATE TABLE IF NOT EXISTS excel_task_row_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    sheet_id BIGINT NOT NULL,
    row_id BIGINT NOT NULL,
    source_row_number INT NOT NULL,
    match_status VARCHAR(32) NOT NULL,
    matched_product_id BIGINT,
    match_reason VARCHAR(255),
    action_type VARCHAR(32) NOT NULL,
    normalized_json LONGTEXT NOT NULL,
    candidates_json LONGTEXT NOT NULL,
    before_json LONGTEXT NOT NULL,
    after_json LONGTEXT NOT NULL,
    issues_json LONGTEXT NOT NULL,
    UNIQUE KEY uk_excel_task_row_result_row (row_id),
    KEY idx_excel_task_result_task_sheet (task_id, sheet_id, source_row_number),
    KEY idx_excel_task_result_match (task_id, match_status)
);

CREATE TABLE IF NOT EXISTS excel_task_commit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary_json LONGTEXT NOT NULL,
    create_time DATETIME NOT NULL,
    UNIQUE KEY uk_excel_task_commit_task (task_id),
    UNIQUE KEY uk_excel_task_commit_idempotency (operator_id, idempotency_key)
);

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

ALTER TABLE product_price_history MODIFY COLUMN after_price DECIMAL(12,2) NULL;

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

CREATE TABLE IF NOT EXISTS inventory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    quantity INT DEFAULT 0,
    location_id VARCHAR(64),
    warning_threshold INT DEFAULT 10,
    last_update_time DATETIME NOT NULL,
    UNIQUE KEY uk_inventory_product_id (product_id)
);

CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(128) NOT NULL,
    name VARCHAR(64),
    phone VARCHAR(30),
    role VARCHAR(32),
    status INT DEFAULT 1,
    create_time DATETIME NOT NULL,
    UNIQUE KEY uk_app_user_username (username)
);

CREATE TABLE IF NOT EXISTS inventory_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(100),
    change_type VARCHAR(32),
    quantity INT DEFAULT 0,
    before_quantity INT DEFAULT 0,
    after_quantity INT DEFAULT 0,
    operator_id BIGINT,
    related_order_id BIGINT,
    remark VARCHAR(255),
    create_time DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT,
    module VARCHAR(32),
    action VARCHAR(64),
    detail VARCHAR(255),
    create_time DATETIME NOT NULL
);
