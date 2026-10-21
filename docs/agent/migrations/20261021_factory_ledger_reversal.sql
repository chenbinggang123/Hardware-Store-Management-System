-- 2026-10-21 工厂账本红字冲销
-- 说明：schema.sql 启动时会自动执行同样的语句；生产环境若设置了 SPRING_SQL_INIT_MODE=never，请发布前手动执行。可重复执行。
-- 2026-10-21 工厂账本红字冲销：旧库补列，可重复执行。
SET @add_factory_tx_status = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE factory_ledger_transaction ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''ACTIVE''',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'factory_ledger_transaction' AND column_name = 'status'
);
PREPARE add_factory_tx_status_stmt FROM @add_factory_tx_status;
EXECUTE add_factory_tx_status_stmt;
DEALLOCATE PREPARE add_factory_tx_status_stmt;

SET @add_factory_tx_reversal = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE factory_ledger_transaction ADD COLUMN reversal_of_id BIGINT NULL',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'factory_ledger_transaction' AND column_name = 'reversal_of_id'
);
PREPARE add_factory_tx_reversal_stmt FROM @add_factory_tx_reversal;
EXECUTE add_factory_tx_reversal_stmt;
DEALLOCATE PREPARE add_factory_tx_reversal_stmt;