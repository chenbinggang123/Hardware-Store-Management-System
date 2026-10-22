-- 2026-10-22 工厂账本分“买的 / 进货的”
-- CUSTOMER = 买的（工厂从我们这里拿货，我们应收）；SUPPLIER = 进货的（我们从源头厂商进货，我们应付）。
-- 同名唯一改为“类型 + 名称”唯一：同一家厂既买我们的货、又卖货给我们时可以各有一本账。
-- schema.sql 启动时会自动执行同样的语句；生产环境若设置了 SPRING_SQL_INIT_MODE=never，请发布前手动执行。可重复执行。
SET @add_factory_ledger_kind = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE factory_ledger ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT ''CUSTOMER''',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'factory_ledger' AND column_name = 'kind'
);
PREPARE add_factory_ledger_kind_stmt FROM @add_factory_ledger_kind;
EXECUTE add_factory_ledger_kind_stmt;
DEALLOCATE PREPARE add_factory_ledger_kind_stmt;

SET @drop_factory_ledger_name_key = (
    SELECT IF(COUNT(*) > 0,
              'ALTER TABLE factory_ledger DROP INDEX uk_factory_ledger_name',
              'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'factory_ledger' AND index_name = 'uk_factory_ledger_name'
);
PREPARE drop_factory_ledger_name_key_stmt FROM @drop_factory_ledger_name_key;
EXECUTE drop_factory_ledger_name_key_stmt;
DEALLOCATE PREPARE drop_factory_ledger_name_key_stmt;

SET @add_factory_ledger_kind_name_key = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE factory_ledger ADD UNIQUE KEY uk_factory_ledger_kind_name (kind, factory_name)',
              'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'factory_ledger' AND index_name = 'uk_factory_ledger_kind_name'
);
PREPARE add_factory_ledger_kind_name_key_stmt FROM @add_factory_ledger_kind_name_key;
EXECUTE add_factory_ledger_kind_name_key_stmt;
DEALLOCATE PREPARE add_factory_ledger_kind_name_key_stmt;
