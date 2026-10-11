-- 2026-10-12 小五对话生成 Excel 文件
-- 用途：让 excel_task_output 同时记录“文件任务输出”和“对话中直接生成的 Excel”。
-- 说明：schema.sql 启动时会自动执行等价的兼容语句；本脚本供需要手工升级的旧 MySQL 环境使用，可重复执行。

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
