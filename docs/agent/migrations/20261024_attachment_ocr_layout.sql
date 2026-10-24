-- 2026-10-24 拍照开单：保存 OCR 每一行在原图上的位置，开单页点开商品时截出原图上的那一行。
-- schema.sql 启动时会自动执行同样的语句；生产环境若设置了 SPRING_SQL_INIT_MODE=never，请发布前手动执行。可重复执行。
SET @add_agent_attachment_ocr_layout = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE agent_attachment ADD COLUMN ocr_layout LONGTEXT NULL',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'agent_attachment' AND column_name = 'ocr_layout'
);
PREPARE add_agent_attachment_ocr_layout_stmt FROM @add_agent_attachment_ocr_layout;
EXECUTE add_agent_attachment_ocr_layout_stmt;
DEALLOCATE PREPARE add_agent_attachment_ocr_layout_stmt;
