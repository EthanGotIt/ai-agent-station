-- Durable After-Sales Agent 完整数据库结构。
-- 包含业务交互与运行审计、售后业务、退款幂等、Outbox/Inbox 共 8 张表。

DROP TABLE IF EXISTS `after_sales_event_consume`;
DROP TABLE IF EXISTS `after_sales_outbox`;
DROP TABLE IF EXISTS `refund_command`;
DROP TABLE IF EXISTS `agent_step`;
DROP TABLE IF EXISTS `agent_run`;
DROP TABLE IF EXISTS `agent_turn`;
DROP TABLE IF EXISTS `after_sales_case`;
DROP TABLE IF EXISTS `demo_order`;

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
    `user_id` varchar(64) NOT NULL,
    `session_id` varchar(64) NOT NULL,
    `user_message` varchar(2000) DEFAULT NULL,
    `order_id` varchar(64) DEFAULT NULL,
    `stage` varchar(64) NOT NULL,
    `checkpoint_id` varchar(36) DEFAULT NULL,
    `resume_token` varchar(36) DEFAULT NULL COMMENT '并发恢复租约，Run完成或失败后释放',
    `next_node` varchar(128) DEFAULT NULL,
    `terminal_reason` varchar(255) DEFAULT NULL,
    `command_id` varchar(36) DEFAULT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`case_id`),
    KEY `idx_after_sales_user_stage` (`user_id`, `stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后 Agent 业务 Case';

CREATE TABLE `agent_turn` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `turn_id` varchar(36) NOT NULL COMMENT '一次外部交互ID',
    `case_id` varchar(36) NOT NULL COMMENT '所属售后Case',
    `session_id` varchar(128) NOT NULL COMMENT '调用方会话标识，仅用于归组',
    `actor_id` varchar(64) NOT NULL COMMENT '用户或审批人标识',
    `turn_type` varchar(32) NOT NULL COMMENT 'START/SUPPLY_INFO/APPROVE/REJECT/CANCEL',
    `input_summary` text COMMENT '本次交互输入摘要',
    `output_summary` text COMMENT '本次交互结果摘要',
    `status` varchar(32) NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/CANCELLED',
    `start_time` datetime(6) NOT NULL,
    `end_time` datetime(6) DEFAULT NULL,
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `update_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_turn_id` (`turn_id`),
    KEY `idx_agent_turn_case` (`case_id`, `create_time`),
    CONSTRAINT `fk_agent_turn_case` FOREIGN KEY (`case_id`) REFERENCES `after_sales_case` (`case_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent外部交互记录';

CREATE TABLE `agent_run` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `run_id` varchar(36) NOT NULL COMMENT '一次状态机执行或恢复尝试ID',
    `turn_id` varchar(36) NOT NULL COMMENT '触发本次执行的Turn',
    `case_id` varchar(36) NOT NULL COMMENT '所属售后Case，同时是状态机 thread key',
    `agent_id` varchar(64) NOT NULL,
    `trigger_type` varchar(32) NOT NULL COMMENT 'START/RESUME/RETRY',
    `attempt_no` int NOT NULL DEFAULT 1,
    `status` varchar(32) NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/CANCELLED',
    `final_summary` text COMMENT '本次执行结果摘要',
    `error_message` varchar(1024) DEFAULT NULL,
    `cancel_reason` varchar(255) DEFAULT NULL,
    `checkpoint_before` varchar(36) DEFAULT NULL,
    `checkpoint_after` varchar(36) DEFAULT NULL,
    `start_time` datetime(6) NOT NULL,
    `end_time` datetime(6) DEFAULT NULL,
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `update_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_run_id` (`run_id`),
    UNIQUE KEY `uk_agent_run_turn_attempt` (`turn_id`, `attempt_no`),
    KEY `idx_agent_run_case` (`case_id`, `create_time`),
    CONSTRAINT `fk_agent_run_turn` FOREIGN KEY (`turn_id`) REFERENCES `agent_turn` (`turn_id`),
    CONSTRAINT `fk_agent_run_case` FOREIGN KEY (`case_id`) REFERENCES `after_sales_case` (`case_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent单次执行记录';

CREATE TABLE `agent_step` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `run_id` varchar(36) NOT NULL,
    `step_id` varchar(36) NOT NULL,
    `step_name` varchar(128) NOT NULL COMMENT '状态机 Action或Tool名称',
    `step_order` int NOT NULL,
    `step_type` varchar(32) NOT NULL COMMENT 'NODE/MODEL/TOOL',
    `status` varchar(32) NOT NULL COMMENT 'SUCCESS/FAILED',
    `output_summary` text,
    `error_message` varchar(1024) DEFAULT NULL,
    `cost_millis` bigint NOT NULL DEFAULT 0,
    `start_time` datetime(6) NOT NULL,
    `end_time` datetime(6) NOT NULL,
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `update_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_step` (`run_id`, `step_id`),
    KEY `idx_agent_step_order` (`run_id`, `step_order`),
    CONSTRAINT `fk_agent_step_agent_run` FOREIGN KEY (`run_id`) REFERENCES `agent_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent节点与工具步骤记录';

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
    `next_attempt_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `locked_by` varchar(64) DEFAULT NULL,
    `locked_until` datetime(6) DEFAULT NULL,
    `last_error` varchar(1024) DEFAULT NULL,
    `delivered_at` datetime(6) DEFAULT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`event_id`),
    KEY `idx_after_sales_outbox_dispatch` (`status`, `next_attempt_at`, `locked_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后领域事件 Outbox';

CREATE TABLE `after_sales_event_consume` (
    `event_id` varchar(36) NOT NULL,
    `consumer_name` varchar(64) NOT NULL,
    `status` varchar(32) NOT NULL,
    `consumed_at` datetime(6) DEFAULT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`event_id`, `consumer_name`),
    KEY `idx_after_sales_consume_status` (`consumer_name`, `status`, `updated_at`),
    CONSTRAINT `fk_after_sales_consume_event` FOREIGN KEY (`event_id`)
        REFERENCES `after_sales_outbox` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后事件消费幂等收件箱';

INSERT INTO `demo_order` (`order_id`, `user_id`, `status`, `delivered_at`) VALUES
    ('ORDER-PAID-001', 'demo-user-1', 'PAID', NULL),
    ('ORDER-DELIVERED-001', 'demo-user-1', 'DELIVERED', DATE_SUB(NOW(6), INTERVAL 3 DAY)),
    ('ORDER-FOREIGN-001', 'demo-user-2', 'PAID', NULL);
