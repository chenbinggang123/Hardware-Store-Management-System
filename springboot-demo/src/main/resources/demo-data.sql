INSERT INTO product (
    id, name, barcode, spec, unit, unit_convert, retail_price, wholesale_price, old_customer_price,
    cost_price, stock, location_id, source_factory, image_url, status, create_time
) VALUES
    (1, '东成充电电钻', '690100000001', '16V 双电', '台', '', 399.00, 360.00, 345.00, 280.00, 24, 'A-01-01', '东成工具厂', '', 1, '2026-03-27 09:00:00'),
    (2, '德力西空气开关', '690100000002', '2P 32A', '个', '', 28.00, 24.00, 23.00, 18.50, 120, 'B-02-03', '德力西电气', '', 1, '2026-03-27 09:10:00'),
    (3, 'PVC 绝缘胶布', '690100000003', '18mm', '卷', '', 3.50, 3.00, 2.80, 1.60, 8, 'C-03-02', '华南辅材', '', 1, '2026-03-27 09:20:00')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    barcode = VALUES(barcode),
    spec = VALUES(spec),
    unit = VALUES(unit),
    unit_convert = VALUES(unit_convert),
    retail_price = VALUES(retail_price),
    wholesale_price = VALUES(wholesale_price),
    old_customer_price = VALUES(old_customer_price),
    cost_price = VALUES(cost_price),
    stock = VALUES(stock),
    location_id = VALUES(location_id),
    source_factory = VALUES(source_factory),
    image_url = VALUES(image_url),
    status = VALUES(status),
    create_time = VALUES(create_time);

INSERT INTO supplier (id, name, contact, phone, address, remark, create_time) VALUES
    (1, '东成工具供应商', '李强', '13800000001', '广州五金城 1 栋 102', '主营电动工具', '2026-03-27 09:30:00'),
    (2, '德力西渠道商', '王敏', '13800000002', '佛山电气市场 3 区 16 号', '主营电气辅材', '2026-03-27 09:35:00')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    contact = VALUES(contact),
    phone = VALUES(phone),
    address = VALUES(address),
    remark = VALUES(remark),
    create_time = VALUES(create_time);

INSERT INTO customer (id, name, type, phone, address, debt, remark, create_time) VALUES
    (1, '宏达装修队', 'B', '13900000001', '番禺区工地项目部', 680.00, '长期合作客户', '2026-03-27 10:00:00'),
    (2, '陈师傅', '老客户', '13900000002', '白云区维修门店', 0.00, '老客户价', '2026-03-27 10:10:00'),
    (3, '门店散客', '零售', '13900000003', '到店自提', 0.00, '零售客户', '2026-03-27 10:20:00')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    type = VALUES(type),
    phone = VALUES(phone),
    address = VALUES(address),
    debt = VALUES(debt),
    remark = VALUES(remark),
    create_time = VALUES(create_time);

INSERT INTO purchase_order (
    id, order_number, supplier_id, operator_id, order_time, total_amount, status, remark, create_time, items_json
) VALUES
    (
        1,
        'PO202603270001',
        1,
        1,
        '2026-03-27 11:00:00',
        5600.00,
        '已入库',
        '首批采购入库',
        '2026-03-27 11:00:00',
        '[{"productId":1,"productName":"东成充电电钻","quantity":20,"price":280.00,"amount":5600.00,"locationId":"A-01-01"}]'
    ),
    (
        2,
        'PO202603270002',
        2,
        1,
        '2026-03-27 14:00:00',
        1480.00,
        '待入库',
        '补货空气开关',
        '2026-03-27 14:00:00',
        '[{"productId":2,"productName":"德力西空气开关","quantity":80,"price":18.50,"amount":1480.00,"locationId":"B-02-03"}]'
    )
ON DUPLICATE KEY UPDATE
    order_number = VALUES(order_number),
    supplier_id = VALUES(supplier_id),
    operator_id = VALUES(operator_id),
    order_time = VALUES(order_time),
    total_amount = VALUES(total_amount),
    status = VALUES(status),
    remark = VALUES(remark),
    create_time = VALUES(create_time),
    items_json = VALUES(items_json);

INSERT INTO sales_order (
    id, order_number, customer_id, operator_id, order_time, total_amount, status, pay_status, received_amount, debt_amount, create_time, items_json
) VALUES
    (
        1,
        'SO202603270001',
        1,
        1,
        '2026-03-27 15:30:00',
        1080.00,
        '已出库',
        '部分',
        400.00,
        680.00,
        '2026-03-27 15:30:00',
        '[{"productId":1,"productName":"东成充电电钻","quantity":3,"price":360.00,"amount":1080.00}]'
    ),
    (
        2,
        'SO202603270002',
        3,
        1,
        '2026-03-27 16:20:00',
        56.00,
        '待出库',
        '未付',
        0.00,
        56.00,
        '2026-03-27 16:20:00',
        '[{"productId":2,"productName":"德力西空气开关","quantity":2,"price":28.00,"amount":56.00}]'
    )
ON DUPLICATE KEY UPDATE
    order_number = VALUES(order_number),
    customer_id = VALUES(customer_id),
    operator_id = VALUES(operator_id),
    order_time = VALUES(order_time),
    total_amount = VALUES(total_amount),
    status = VALUES(status),
    pay_status = VALUES(pay_status),
    received_amount = VALUES(received_amount),
    debt_amount = VALUES(debt_amount),
    create_time = VALUES(create_time),
    items_json = VALUES(items_json);

INSERT INTO inventory (id, product_id, quantity, location_id, warning_threshold, last_update_time) VALUES
    (1, 1, 24, 'A-01-01', 10, '2026-03-27 15:30:00'),
    (2, 2, 120, 'B-02-03', 20, '2026-03-27 09:10:00'),
    (3, 3, 8, 'C-03-02', 10, '2026-03-27 09:20:00')
ON DUPLICATE KEY UPDATE
    product_id = VALUES(product_id),
    quantity = VALUES(quantity),
    location_id = VALUES(location_id),
    warning_threshold = VALUES(warning_threshold),
    last_update_time = VALUES(last_update_time);

INSERT INTO app_user (id, username, password, name, phone, role, status, create_time) VALUES
    (1, 'admin', '$2a$10$fhDff3zZkxQG6Zy3bgNv/.gbZ5WiznJQzagd8qCeLQWwOXGMVBDHO', '系统管理员', '13600000000', 'ADMIN', 1, '2026-03-27 08:30:00'),
    (2, 'clerk01', '$2a$10$fhDff3zZkxQG6Zy3bgNv/.gbZ5WiznJQzagd8qCeLQWwOXGMVBDHO', '营业员小张', '13600000001', 'CLERK', 1, '2026-03-27 08:40:00')
ON DUPLICATE KEY UPDATE
    id = VALUES(id);

INSERT INTO inventory_log (
    id, product_id, product_name, change_type, quantity, before_quantity, after_quantity, operator_id, related_order_id, remark, create_time
) VALUES
    (1, 1, '东成充电电钻', '入库', 20, 4, 24, 1, 1, '采购单入库：PO202603270001', '2026-03-27 11:05:00'),
    (2, 1, '东成充电电钻', '出库', 3, 27, 24, 1, 1, '销售单出库：SO202603270001', '2026-03-27 15:35:00')
ON DUPLICATE KEY UPDATE
    product_id = VALUES(product_id),
    product_name = VALUES(product_name),
    change_type = VALUES(change_type),
    quantity = VALUES(quantity),
    before_quantity = VALUES(before_quantity),
    after_quantity = VALUES(after_quantity),
    operator_id = VALUES(operator_id),
    related_order_id = VALUES(related_order_id),
    remark = VALUES(remark),
    create_time = VALUES(create_time);

INSERT INTO operation_log (id, operator_id, module, action, detail, create_time) VALUES
    (1, 1, 'PRODUCT', 'CREATE', '初始化商品数据', '2026-03-27 09:00:00'),
    (2, 1, 'PURCHASE', 'STOCK_IN', '采购单入库：PO202603270001', '2026-03-27 11:05:00'),
    (3, 1, 'SALES', 'STOCK_OUT', '销售单出库：SO202603270001', '2026-03-27 15:35:00')
ON DUPLICATE KEY UPDATE
    operator_id = VALUES(operator_id),
    module = VALUES(module),
    action = VALUES(action),
    detail = VALUES(detail),
    create_time = VALUES(create_time);
