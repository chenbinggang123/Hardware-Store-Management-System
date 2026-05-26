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
