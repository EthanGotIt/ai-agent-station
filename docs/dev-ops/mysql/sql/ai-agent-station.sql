-- Durable After-Sales Agent 完整数据库结构。
-- 包含业务运行审计、售后业务、退款幂等、Outbox 和 LangGraph4j checkpoint 共 8 张表。

DROP TABLE IF EXISTS `LANGRAPH4J_CHECKPOINT`;
DROP TABLE IF EXISTS `LANGRAPH4J_THREAD`;
DROP TABLE IF EXISTS `after_sales_outbox`;
DROP TABLE IF EXISTS `refund_command`;
DROP TABLE IF EXISTS `after_sales_case`;
DROP TABLE IF EXISTS `demo_order`;
DROP TABLE IF EXISTS `agent_step_run`;
DROP TABLE IF EXISTS `agent_run`;

CREATE TABLE `agent_run` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_id` varchar(64) NOT NULL COMMENT '一次业务执行ID',
    `agent_id` varchar(64) NOT NULL COMMENT 'Agent类型',
    `session_id` varchar(128) NOT NULL COMMENT '调用方会话ID，仅用于归组多个Run',
    `user_message` text COMMENT '用户原始输入',
    `status` varchar(32) NOT NULL COMMENT 'INIT/RUNNING/SUCCESS/FAILED/CANCELLED',
    `final_summary` mediumtext COMMENT '最终结果摘要',
    `error_message` varchar(1024) DEFAULT NULL COMMENT '失败原因',
    `cancel_reason` varchar(255) DEFAULT NULL COMMENT '取消原因',
    `start_time` datetime(6) DEFAULT NULL COMMENT '开始时间',
    `end_time` datetime(6) DEFAULT NULL COMMENT '结束时间',
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `update_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_run_id` (`run_id`),
    KEY `idx_agent_run_session` (`session_id`, `create_time`),
    KEY `idx_agent_run_status` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent业务运行记录';

CREATE TABLE `agent_step_run` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_id` varchar(64) NOT NULL COMMENT '所属业务执行ID',
    `step_id` varchar(64) NOT NULL COMMENT 'checkpoint或步骤ID',
    `step_name` varchar(255) DEFAULT NULL COMMENT '步骤名称',
    `step_order` int NOT NULL DEFAULT '0' COMMENT '步骤序号',
    `step_type` varchar(32) DEFAULT NULL COMMENT '步骤类型',
    `status` varchar(32) NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/SKIPPED/CANCELLED',
    `output_summary` mediumtext COMMENT '步骤摘要',
    `error_message` varchar(1024) DEFAULT NULL COMMENT '步骤错误',
    `cost_millis` bigint NOT NULL DEFAULT '0' COMMENT '耗时毫秒',
    `start_time` datetime(6) DEFAULT NULL COMMENT '开始时间',
    `end_time` datetime(6) DEFAULT NULL COMMENT '结束时间',
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `update_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_step_run` (`run_id`, `step_id`),
    KEY `idx_agent_step_order` (`run_id`, `step_order`),
    CONSTRAINT `fk_agent_step_run` FOREIGN KEY (`run_id`) REFERENCES `agent_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent步骤运行记录';

CREATE TABLE `demo_order` (
    `order_id` varchar(64) NOT NULL,
    `user_id` varchar(64) NOT NULL,
    `status` varchar(32) NOT NULL,
    `delivered_at` datetime(6) DEFAULT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`order_id`),
    KEY `idx_demo_order_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后 Agent 本地演示订单';

CREATE TABLE `after_sales_case` (
    `case_id` varchar(36) NOT NULL,
    `run_id` varchar(36) NOT NULL,
    `user_id` varchar(64) NOT NULL,
    `session_id` varchar(64) NOT NULL,
    `user_message` varchar(2000) DEFAULT NULL,
    `order_id` varchar(64) DEFAULT NULL,
    `stage` varchar(64) NOT NULL,
    `checkpoint_id` varchar(36) DEFAULT NULL,
    `next_node` varchar(128) DEFAULT NULL,
    `terminal_reason` varchar(255) DEFAULT NULL,
    `command_id` varchar(36) DEFAULT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`case_id`),
    UNIQUE KEY `uk_after_sales_run` (`run_id`),
    KEY `idx_after_sales_user_stage` (`user_id`, `stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后 Agent 业务 Case';

CREATE TABLE `refund_command` (
    `command_id` varchar(36) NOT NULL,
    `case_id` varchar(36) NOT NULL,
    `order_id` varchar(64) NOT NULL,
    `user_id` varchar(64) NOT NULL,
    `idempotency_key` varchar(96) NOT NULL,
    `status` varchar(32) NOT NULL,
    `failure_reason` varchar(255) DEFAULT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`command_id`),
    UNIQUE KEY `uk_refund_idempotency` (`idempotency_key`),
    KEY `idx_refund_case_status` (`case_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款幂等命令账本';

CREATE TABLE `after_sales_outbox` (
    `event_id` varchar(36) NOT NULL,
    `aggregate_id` varchar(36) NOT NULL,
    `event_type` varchar(64) NOT NULL,
    `payload` json NOT NULL,
    `status` varchar(32) NOT NULL,
    `retry_count` int NOT NULL DEFAULT 0,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`event_id`),
    KEY `idx_after_sales_outbox_dispatch` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后领域事件 Outbox';

INSERT INTO `demo_order` (`order_id`, `user_id`, `status`, `delivered_at`) VALUES
    ('ORDER-PAID-001', 'demo-user-1', 'PAID', NULL),
    ('ORDER-DELIVERED-001', 'demo-user-1', 'DELIVERED', DATE_SUB(NOW(6), INTERVAL 3 DAY)),
    ('ORDER-FOREIGN-001', 'demo-user-2', 'PAID', NULL);

-- LangGraph4j MysqlSaver 1.8.20 checkpoint 表。
CREATE TABLE `LANGRAPH4J_THREAD` (
    `thread_id` varchar(36) NOT NULL,
    `thread_name` varchar(255) DEFAULT NULL,
    `is_released` boolean NOT NULL DEFAULT FALSE,
    PRIMARY KEY (`thread_id`),
    UNIQUE KEY `IDX_LANGRAPH4J_THREAD_NAME_RELEASED` (`thread_name`, `is_released`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `LANGRAPH4J_CHECKPOINT` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT UNIQUE KEY,
    `checkpoint_id` varchar(36) NOT NULL,
    `thread_id` varchar(36) NOT NULL,
    `node_id` varchar(255) DEFAULT NULL,
    `next_node_id` varchar(255) DEFAULT NULL,
    `state_data` json NOT NULL,
    `saved_at` timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`checkpoint_id`),
    KEY `idx_langgraph4j_checkpoint_thread` (`thread_id`, `saved_at`),
    CONSTRAINT `LANGRAPH4J_FK_THREAD` FOREIGN KEY (`thread_id`)
        REFERENCES `LANGRAPH4J_THREAD` (`thread_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
