-- 持久化 Workflow 结果触发的 Agent 续跑 Turn；历史 Turn 保持原有输入类型。
SET @cga_schema = DATABASE();

SET @cga_sql = IF(
        (SELECT COUNT(*)
         FROM INFORMATION_SCHEMA.COLUMNS
         WHERE TABLE_SCHEMA = @cga_schema
           AND TABLE_NAME = 'AGENT_TURN'
           AND COLUMN_NAME = 'CONTINUATION_JSON') = 0,
        'ALTER TABLE AGENT_TURN ADD COLUMN CONTINUATION_JSON LONGTEXT DEFAULT NULL AFTER ORDER_ACTION_JSON',
        'SELECT 1');
PREPARE cga_statement FROM @cga_sql;
EXECUTE cga_statement;
DEALLOCATE PREPARE cga_statement;

-- 历史行保持 NULL；迁移不重写任何既有 Turn 事实。
