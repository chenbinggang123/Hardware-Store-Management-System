-- 清理 demo-data.sql 灌进来的演示数据（商品、库存、供应商、客户、演示订单）。
-- 只按演示数据特有的条码、手机号、单号匹配，不会动你自己录入的数据。
-- 登录用户 admin / clerk01 和操作日志不删，免得登不上去。
-- 执行前请先备份；预览用 docs/cleanup-demo-data-preview.sql。

DELETE FROM inventory_log WHERE product_id IN (SELECT id FROM product WHERE barcode IN ('690100000001','690100000002','690100000003'));
DELETE FROM inventory WHERE product_id IN (SELECT id FROM product WHERE barcode IN ('690100000001','690100000002','690100000003'));
DELETE FROM product_price_history WHERE product_id IN (SELECT id FROM product WHERE barcode IN ('690100000001','690100000002','690100000003'));
DELETE FROM product WHERE barcode IN ('690100000001','690100000002','690100000003');
DELETE FROM purchase_order WHERE order_number LIKE 'PO20260327000_';
DELETE FROM sales_order WHERE order_number LIKE 'SO20260327000_';
DELETE FROM supplier WHERE phone IN ('13800000001','13800000002');
DELETE FROM customer WHERE phone IN ('13900000001','13900000002','13900000003');
