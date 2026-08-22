-- 现有基线库可能已经存在业务表，因此该迁移只做可重复的增量补齐。
-- MySQL 8.4 不支持这里所需的多列 IF NOT EXISTS 语法，改用元数据判断。
-- 空库仍必须先执行 docs/dev-ops/mysql/commerce-guardian-agent.sql。
SET @cga_schema = DATABASE();

SET @cga_sql = IF(
        (SELECT COUNT(*)
         FROM INFORMATION_SCHEMA.COLUMNS
         WHERE TABLE_SCHEMA = @cga_schema
           AND TABLE_NAME = 'AGENT_WORKFLOW_QUESTION'
           AND COLUMN_NAME = 'ANSWER_TURN_ID') = 0,
        'ALTER TABLE AGENT_WORKFLOW_QUESTION ADD COLUMN ANSWER_TURN_ID VARCHAR(64) DEFAULT NULL AFTER VERSION_NO',
        'SELECT 1');
PREPARE cga_statement FROM @cga_sql;
EXECUTE cga_statement;
DEALLOCATE PREPARE cga_statement;

SET @cga_sql = IF(
        (SELECT COUNT(*)
         FROM INFORMATION_SCHEMA.COLUMNS
         WHERE TABLE_SCHEMA = @cga_schema
           AND TABLE_NAME = 'AGENT_WORKFLOW_QUESTION'
           AND COLUMN_NAME = 'ANSWER_ENQUEUE_STATUS') = 0,
        'ALTER TABLE AGENT_WORKFLOW_QUESTION ADD COLUMN ANSWER_ENQUEUE_STATUS VARCHAR(32) NOT NULL DEFAULT ''AVAILABLE'' AFTER ANSWER_TURN_ID',
        'SELECT 1');
PREPARE cga_statement FROM @cga_sql;
EXECUTE cga_statement;
DEALLOCATE PREPARE cga_statement;
