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

CREATE TABLE IF NOT EXISTS factory_ledger (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ledger_no VARCHAR(64) NOT NULL,
    factory_name VARCHAR(100) NOT NULL,
    contact_person VARCHAR(50),
    phone VARCHAR(30),
    start_date DATE NOT NULL,
    settlement_day INT NOT NULL DEFAULT 31,
    opening_balance_cents BIGINT NOT NULL DEFAULT 0,
    remark VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    UNIQUE KEY uk_factory_ledger_no (ledger_no),
    UNIQUE KEY uk_factory_ledger_name (factory_name),
    KEY idx_factory_ledger_update (update_time)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS factory_ledger_transaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    transaction_no VARCHAR(64) NOT NULL,
    ledger_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    transaction_date DATE NOT NULL,
    receipt_no VARCHAR(64),
    amount_cents BIGINT NOT NULL,
    items_json LONGTEXT,
    remark VARCHAR(255),
    source VARCHAR(32) NOT NULL,
    operator_id BIGINT,
    create_time DATETIME NOT NULL,
    UNIQUE KEY uk_factory_transaction_no (transaction_no),
    UNIQUE KEY uk_factory_ledger_receipt (ledger_id, receipt_no),
    KEY idx_factory_transaction_ledger_date (ledger_id, transaction_date, create_time)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS factory_ledger_settlement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ledger_id BIGINT NOT NULL,
    settlement_month VARCHAR(7) NOT NULL,
    status VARCHAR(32) NOT NULL,
    operator_id BIGINT,
    update_time DATETIME NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_factory_settlement_month (ledger_id, settlement_month),
    KEY idx_factory_settlement_status (settlement_month, status)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS source_purchase_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    purchase_order_id BIGINT,
    attachment_id BIGINT NOT NULL,
    supplier_id BIGINT,
    supplier_name VARCHAR(100) NOT NULL,
    source_order_no VARCHAR(100),
    order_date DATE NOT NULL,
    buyer_name VARCHAR(100),
    handler_name VARCHAR(100),
    declared_total_amount DECIMAL(12, 2),
    calculated_total_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    receive_stock TINYINT NOT NULL DEFAULT 0,
    remark VARCHAR(500),
    operator_id BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    create_time DATETIME NOT NULL,
    items_json LONGTEXT NOT NULL,
    UNIQUE KEY uk_source_purchase_idempotency (idempotency_key),
    UNIQUE KEY uk_source_purchase_supplier_order (supplier_name, source_order_no),
    KEY idx_source_purchase_time (order_date, create_time),
    KEY idx_source_purchase_attachment (attachment_id),
    KEY idx_source_purchase_order (purchase_order_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

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
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

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
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

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
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

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
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS file_center_metadata (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    purpose_code VARCHAR(32) NOT NULL DEFAULT 'UNCLASSIFIED',
    related_type VARCHAR(32) NOT NULL DEFAULT 'NONE',
    related_id BIGINT,
    related_name VARCHAR(255),
    note VARCHAR(1000),
    manual_upload BOOLEAN NOT NULL DEFAULT FALSE,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    UNIQUE KEY uk_file_center_source (operator_id, source_kind, source_id),
    KEY idx_file_center_operator_time (operator_id, update_time)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS file_center_activity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    metadata_id BIGINT NOT NULL,
    activity_type VARCHAR(32) NOT NULL,
    title VARCHAR(100) NOT NULL,
    detail VARCHAR(500),
    create_time DATETIME NOT NULL,
    KEY idx_file_center_activity_time (metadata_id, create_time)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS account_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT NOT NULL,
    direction VARCHAR(20) NOT NULL,
    counterparty_type VARCHAR(20) NOT NULL,
    counterparty_id BIGINT NOT NULL,
    counterparty_name VARCHAR(255) NOT NULL,
    document_number VARCHAR(100),
    document_date DATE NOT NULL,
    due_date DATE,
    total_amount DECIMAL(14,2) NOT NULL,
    settled_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    outstanding_amount DECIMAL(14,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    note VARCHAR(1000),
    file_source_kind VARCHAR(32),
    file_source_id BIGINT,
    business_source_type VARCHAR(32),
    business_source_id BIGINT,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    KEY idx_account_document_operator_date (operator_id, document_date),
    KEY idx_account_document_counterparty (counterparty_type, counterparty_id),
    KEY idx_account_document_status (operator_id, direction, status),
    UNIQUE KEY uk_account_document_business_source (operator_id, business_source_type, business_source_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS account_payment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_id BIGINT NOT NULL,
    direction VARCHAR(20) NOT NULL,
    counterparty_type VARCHAR(20) NOT NULL,
    counterparty_id BIGINT NOT NULL,
    counterparty_name VARCHAR(255) NOT NULL,
    payment_date DATE NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    unallocated_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    payment_method VARCHAR(50),
    note VARCHAR(500),
    create_time DATETIME NOT NULL,
    KEY idx_account_payment_operator_date (operator_id, payment_date),
    KEY idx_account_payment_counterparty (counterparty_type, counterparty_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 兼容已经存在的账款表，补充订单来源和未分配金额。
SET @add_account_document_source_type = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE account_document ADD COLUMN business_source_type VARCHAR(32) NULL AFTER file_source_id',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'account_document' AND column_name = 'business_source_type'
);
PREPARE add_account_document_source_type_stmt FROM @add_account_document_source_type;
EXECUTE add_account_document_source_type_stmt;
DEALLOCATE PREPARE add_account_document_source_type_stmt;

SET @add_account_document_due_date = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE account_document ADD COLUMN due_date DATE NULL AFTER document_date',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'account_document' AND column_name = 'due_date'
);
PREPARE add_account_document_due_date_stmt FROM @add_account_document_due_date;
EXECUTE add_account_document_due_date_stmt;
DEALLOCATE PREPARE add_account_document_due_date_stmt;

SET @add_account_document_source_id = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE account_document ADD COLUMN business_source_id BIGINT NULL AFTER business_source_type',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'account_document' AND column_name = 'business_source_id'
);
PREPARE add_account_document_source_id_stmt FROM @add_account_document_source_id;
EXECUTE add_account_document_source_id_stmt;
DEALLOCATE PREPARE add_account_document_source_id_stmt;

SET @add_account_document_source_index = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE account_document ADD UNIQUE KEY uk_account_document_business_source (operator_id, business_source_type, business_source_id)',
              'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'account_document' AND index_name = 'uk_account_document_business_source'
);
PREPARE add_account_document_source_index_stmt FROM @add_account_document_source_index;
EXECUTE add_account_document_source_index_stmt;
DEALLOCATE PREPARE add_account_document_source_index_stmt;

SET @add_account_payment_unallocated = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE account_payment ADD COLUMN unallocated_amount DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER amount',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'account_payment' AND column_name = 'unallocated_amount'
);
PREPARE add_account_payment_unallocated_stmt FROM @add_account_payment_unallocated;
EXECUTE add_account_payment_unallocated_stmt;
DEALLOCATE PREPARE add_account_payment_unallocated_stmt;

CREATE TABLE IF NOT EXISTS payment_allocation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    payment_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    create_time DATETIME NOT NULL,
    KEY idx_payment_allocation_document (document_id, create_time),
    KEY idx_payment_allocation_payment (payment_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 兼容旧库的 MySQL utf8（最多 3 字节）。Agent 输入、输出与工具结果可能包含 Emoji，必须升级为 utf8mb4。
SET @convert_agent_conversation_utf8mb4 = (
    SELECT IF(COUNT(*) > 0,
              'ALTER TABLE agent_conversation CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'agent_conversation'
      AND character_set_name IS NOT NULL AND character_set_name <> 'utf8mb4'
);
PREPARE convert_agent_conversation_utf8mb4_stmt FROM @convert_agent_conversation_utf8mb4;
EXECUTE convert_agent_conversation_utf8mb4_stmt;
DEALLOCATE PREPARE convert_agent_conversation_utf8mb4_stmt;

SET @convert_agent_run_utf8mb4 = (
    SELECT IF(COUNT(*) > 0,
              'ALTER TABLE agent_run CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'agent_run'
      AND character_set_name IS NOT NULL AND character_set_name <> 'utf8mb4'
);
PREPARE convert_agent_run_utf8mb4_stmt FROM @convert_agent_run_utf8mb4;
EXECUTE convert_agent_run_utf8mb4_stmt;
DEALLOCATE PREPARE convert_agent_run_utf8mb4_stmt;

SET @convert_agent_attachment_utf8mb4 = (
    SELECT IF(COUNT(*) > 0,
              'ALTER TABLE agent_attachment CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'agent_attachment'
      AND character_set_name IS NOT NULL AND character_set_name <> 'utf8mb4'
);
PREPARE convert_agent_attachment_utf8mb4_stmt FROM @convert_agent_attachment_utf8mb4;
EXECUTE convert_agent_attachment_utf8mb4_stmt;
DEALLOCATE PREPARE convert_agent_attachment_utf8mb4_stmt;

SET @convert_agent_tool_call_utf8mb4 = (
    SELECT IF(COUNT(*) > 0,
              'ALTER TABLE agent_tool_call CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'agent_tool_call'
      AND character_set_name IS NOT NULL AND character_set_name <> 'utf8mb4'
);
PREPARE convert_agent_tool_call_utf8mb4_stmt FROM @convert_agent_tool_call_utf8mb4;
EXECUTE convert_agent_tool_call_utf8mb4_stmt;
DEALLOCATE PREPARE convert_agent_tool_call_utf8mb4_stmt;

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
    source_format VARCHAR(32),
    parse_method VARCHAR(32),
    ocr_provider VARCHAR(64),
    ocr_model_version VARCHAR(100),
    ocr_request_id VARCHAR(160),
    ocr_result_json LONGTEXT,
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

SET @add_excel_task_source_format = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task ADD COLUMN source_format VARCHAR(32) NULL AFTER mime_type',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'excel_task' AND column_name = 'source_format'
);
PREPARE add_excel_task_source_format_stmt FROM @add_excel_task_source_format;
EXECUTE add_excel_task_source_format_stmt;
DEALLOCATE PREPARE add_excel_task_source_format_stmt;

SET @add_excel_task_parse_method = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task ADD COLUMN parse_method VARCHAR(32) NULL AFTER source_format',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'excel_task' AND column_name = 'parse_method'
);
PREPARE add_excel_task_parse_method_stmt FROM @add_excel_task_parse_method;
EXECUTE add_excel_task_parse_method_stmt;
DEALLOCATE PREPARE add_excel_task_parse_method_stmt;

SET @add_excel_task_ocr_provider = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task ADD COLUMN ocr_provider VARCHAR(64) NULL AFTER parse_method',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'excel_task' AND column_name = 'ocr_provider'
);
PREPARE add_excel_task_ocr_provider_stmt FROM @add_excel_task_ocr_provider;
EXECUTE add_excel_task_ocr_provider_stmt;
DEALLOCATE PREPARE add_excel_task_ocr_provider_stmt;

SET @add_excel_task_ocr_model_version = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task ADD COLUMN ocr_model_version VARCHAR(100) NULL AFTER ocr_provider',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'excel_task' AND column_name = 'ocr_model_version'
);
PREPARE add_excel_task_ocr_model_version_stmt FROM @add_excel_task_ocr_model_version;
EXECUTE add_excel_task_ocr_model_version_stmt;
DEALLOCATE PREPARE add_excel_task_ocr_model_version_stmt;

SET @add_excel_task_ocr_request_id = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task ADD COLUMN ocr_request_id VARCHAR(160) NULL AFTER ocr_model_version',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'excel_task' AND column_name = 'ocr_request_id'
);
PREPARE add_excel_task_ocr_request_id_stmt FROM @add_excel_task_ocr_request_id;
EXECUTE add_excel_task_ocr_request_id_stmt;
DEALLOCATE PREPARE add_excel_task_ocr_request_id_stmt;

SET @add_excel_task_ocr_result_json = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task ADD COLUMN ocr_result_json LONGTEXT NULL AFTER ocr_request_id',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'excel_task' AND column_name = 'ocr_result_json'
);
PREPARE add_excel_task_ocr_result_json_stmt FROM @add_excel_task_ocr_result_json;
EXECUTE add_excel_task_ocr_result_json_stmt;
DEALLOCATE PREPARE add_excel_task_ocr_result_json_stmt;

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

-- 输出文件记录：既保存 Excel 文件任务的报价/导出文件，也保存小五在对话中直接生成的文件。
-- task_id 允许为空；对话生成的文件通过 source_type / run_id 关联，expires_at 为空表示永久保存，由用户自行删除。
CREATE TABLE IF NOT EXISTS excel_task_output (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NULL,
    operator_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'EXCEL_TASK',
    source_id BIGINT NULL,
    run_id BIGINT NULL,
    conversation_id BIGINT NULL,
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
    KEY idx_excel_output_task_time (task_id, create_time),
    KEY idx_excel_output_run (run_id),
    KEY idx_excel_output_operator_time (operator_id, source_type, create_time)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 兼容已经存在的 excel_task_output：放开 task_id，并补充对话生成文件需要的来源列与索引。
ALTER TABLE excel_task_output MODIFY COLUMN task_id BIGINT NULL;

SET @add_excel_output_source_type = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task_output ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT ''EXCEL_TASK'' AFTER operator_id',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'excel_task_output' AND column_name = 'source_type'
);
PREPARE add_excel_output_source_type_stmt FROM @add_excel_output_source_type;
EXECUTE add_excel_output_source_type_stmt;
DEALLOCATE PREPARE add_excel_output_source_type_stmt;

SET @add_excel_output_source_id = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task_output ADD COLUMN source_id BIGINT NULL AFTER source_type',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'excel_task_output' AND column_name = 'source_id'
);
PREPARE add_excel_output_source_id_stmt FROM @add_excel_output_source_id;
EXECUTE add_excel_output_source_id_stmt;
DEALLOCATE PREPARE add_excel_output_source_id_stmt;

SET @add_excel_output_run_id = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task_output ADD COLUMN run_id BIGINT NULL AFTER source_id',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'excel_task_output' AND column_name = 'run_id'
);
PREPARE add_excel_output_run_id_stmt FROM @add_excel_output_run_id;
EXECUTE add_excel_output_run_id_stmt;
DEALLOCATE PREPARE add_excel_output_run_id_stmt;

SET @add_excel_output_conversation_id = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task_output ADD COLUMN conversation_id BIGINT NULL AFTER run_id',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'excel_task_output' AND column_name = 'conversation_id'
);
PREPARE add_excel_output_conversation_id_stmt FROM @add_excel_output_conversation_id;
EXECUTE add_excel_output_conversation_id_stmt;
DEALLOCATE PREPARE add_excel_output_conversation_id_stmt;

SET @add_excel_output_run_index = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task_output ADD KEY idx_excel_output_run (run_id)',
              'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'excel_task_output' AND index_name = 'idx_excel_output_run'
);
PREPARE add_excel_output_run_index_stmt FROM @add_excel_output_run_index;
EXECUTE add_excel_output_run_index_stmt;
DEALLOCATE PREPARE add_excel_output_run_index_stmt;

SET @add_excel_output_operator_index = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE excel_task_output ADD KEY idx_excel_output_operator_time (operator_id, source_type, create_time)',
              'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'excel_task_output' AND index_name = 'idx_excel_output_operator_time'
);
PREPARE add_excel_output_operator_index_stmt FROM @add_excel_output_operator_index;
EXECUTE add_excel_output_operator_index_stmt;
DEALLOCATE PREPARE add_excel_output_operator_index_stmt;

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
