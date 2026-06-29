-- AI Agent Station MySQL seed data
-- 当前数据保留单 Spring AI 2 Advisor Chain 智能体，并内置企业知识助手常用的只读 MCP 工具配置。

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+08:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

-- 智能体配置
DROP TABLE IF EXISTS `ai_agent`;
CREATE TABLE `ai_agent` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `agent_id` varchar(64) NOT NULL COMMENT '智能体业务ID，前端请求使用',
    `agent_name` varchar(50) NOT NULL COMMENT '智能体名称',
    `description` varchar(255) DEFAULT NULL COMMENT '智能体说明',
    `channel` varchar(32) DEFAULT NULL COMMENT '渠道类型，当前统一为 agent',
    `status` tinyint(1) DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI智能体配置表';

LOCK TABLES `ai_agent` WRITE;
INSERT INTO `ai_agent` (`id`, `agent_id`, `agent_name`, `description`, `channel`, `status`, `create_time`, `update_time`)
VALUES
    (1, 'agent-java-knowledge', 'Spring AI Evidence Assistant', '面向 Java 项目知识与技术资料调研的 Spring AI 2 证据治理助手', 'agent', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
UNLOCK TABLES;

-- 对话客户端配置
DROP TABLE IF EXISTS `ai_client`;
CREATE TABLE `ai_client` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `client_id` varchar(64) NOT NULL COMMENT '客户端业务ID',
    `client_name` varchar(50) NOT NULL COMMENT '客户端名称',
    `description` varchar(1024) DEFAULT NULL COMMENT '客户端职责说明',
    `status` tinyint(1) DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_client_id` (`client_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI客户端配置表';

LOCK TABLES `ai_client` WRITE;
INSERT INTO `ai_client` (`id`, `client_id`, `client_name`, `description`, `status`, `create_time`, `update_time`)
VALUES
    (1, 'client-advisor-main', 'Spring AI Advisor 主执行客户端', '通过 ChatClient 与 Advisor Chain 完成证据检索、工具调用和最终回答', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
UNLOCK TABLES;

-- 模型 API 配置
DROP TABLE IF EXISTS `ai_client_api`;
CREATE TABLE `ai_client_api` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `api_id` varchar(64) NOT NULL COMMENT '模型接口业务ID',
    `base_url` varchar(255) NOT NULL COMMENT 'API 基础地址',
    `api_key` varchar(255) NOT NULL COMMENT 'API 密钥占位符，运行时从环境变量解析',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_api_id` (`api_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模型 API 配置表';

LOCK TABLES `ai_client_api` WRITE;
INSERT INTO `ai_client_api` (`id`, `api_id`, `base_url`, `api_key`, `status`, `create_time`, `update_time`)
VALUES
    (1, 'api-dashscope-openai', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '${OPENAI_API_KEY}', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
UNLOCK TABLES;

-- 知识库配置表保留，当前不写入 RAG 种子数据
DROP TABLE IF EXISTS `ai_client_rag_order`;
CREATE TABLE `ai_client_rag_order` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `rag_id` varchar(50) NOT NULL COMMENT '知识库业务ID',
    `rag_name` varchar(50) NOT NULL COMMENT '知识库名称',
    `knowledge_tag` varchar(50) NOT NULL COMMENT '知识标签',
    `status` tinyint(1) DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rag_id` (`rag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库配置表';

LOCK TABLES `ai_client_rag_order` WRITE;
INSERT INTO `ai_client_rag_order` (`id`, `rag_id`, `rag_name`, `knowledge_tag`, `status`, `create_time`, `update_time`)
VALUES
    (1, 'rag-agent-station', 'AI Agent Station 知识库', 'ai-agent-station', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
UNLOCK TABLES;

-- RAG 文档主表（Markdown Parent 元数据）
DROP TABLE IF EXISTS `ai_rag_document`;
CREATE TABLE `ai_rag_document` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `rag_id` varchar(64) NOT NULL COMMENT '知识库业务ID',
    `doc_id` varchar(64) NOT NULL COMMENT '文档业务ID',
    `title` varchar(255) DEFAULT NULL COMMENT '文档标题',
    `source` varchar(255) DEFAULT NULL COMMENT '来源标识',
    `summary` text COMMENT '文档摘要',
    `metadata_json` text COMMENT '扩展元数据 JSON，包含 doc_type、knowledge_tag 等',
    `status` tinyint(1) DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rag_doc` (`rag_id`, `doc_id`),
    KEY `idx_doc_status` (`doc_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG 文档主表';

-- RAG 分块表（Parent-Child 元数据）
DROP TABLE IF EXISTS `ai_rag_chunk`;
CREATE TABLE `ai_rag_chunk` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `rag_id` varchar(64) NOT NULL COMMENT '知识库业务ID',
    `doc_id` varchar(64) NOT NULL COMMENT '文档业务ID',
    `chunk_id` varchar(64) NOT NULL COMMENT '分块业务ID',
    `parent_chunk_id` varchar(64) DEFAULT NULL COMMENT '父块业务ID，子块命中后回溯使用',
    `chunk_level` tinyint DEFAULT '1' COMMENT '分块层级：1父块，2子块',
    `chunk_type` varchar(32) DEFAULT 'text' COMMENT '分块类型',
    `chunk_text` mediumtext COMMENT '分块正文',
    `metadata_json` text COMMENT '扩展元数据 JSON，包含 title、section_title、chunk_order 等',
    `status` tinyint(1) DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_chunk` (`doc_id`, `chunk_id`),
    KEY `idx_rag_doc` (`rag_id`, `doc_id`),
    KEY `idx_parent_chunk` (`parent_chunk_id`),
    KEY `idx_chunk_status` (`doc_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG 父子分块表';

-- 系统提示词配置
DROP TABLE IF EXISTS `ai_client_system_prompt`;
CREATE TABLE `ai_client_system_prompt` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `prompt_id` varchar(64) NOT NULL COMMENT '提示词业务ID',
    `prompt_name` varchar(128) NOT NULL COMMENT '提示词名称',
    `prompt_content` text NOT NULL COMMENT '提示词内容',
    `description` varchar(1024) DEFAULT NULL COMMENT '提示词说明',
    `status` tinyint(1) DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_prompt_id` (`prompt_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统提示词配置表';

LOCK TABLES `ai_client_system_prompt` WRITE;
INSERT INTO `ai_client_system_prompt` (`id`, `prompt_id`, `prompt_name`, `prompt_content`, `description`, `status`, `create_time`, `update_time`)
VALUES
    (1, 'prompt-evidence-answer', 'Evidence 回答提示词', '你是企业 Java 项目知识与技术资料助手。回答必须优先基于项目知识和可归因 evidence，并使用 E1、E2 等证据编号引用；工具失败、证据冲突或证据不足时不得编造。', 'Spring AI Advisor Chain 证据约束回答', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
UNLOCK TABLES;

-- MCP 工具配置
DROP TABLE IF EXISTS `ai_client_tool_mcp`;
CREATE TABLE `ai_client_tool_mcp` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `mcp_id` varchar(64) NOT NULL COMMENT 'MCP 工具业务ID',
    `mcp_name` varchar(50) NOT NULL COMMENT 'MCP 工具名称',
    `transport_type` varchar(20) NOT NULL COMMENT '传输协议：stdio 或 streamable_http',
    `transport_config` varchar(1024) DEFAULT NULL COMMENT '传输配置 JSON',
    `request_timeout` int DEFAULT '3' COMMENT '请求及初始化超时时间，单位分钟',
    `status` tinyint(1) DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mcp_id` (`mcp_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MCP 工具配置表';

LOCK TABLES `ai_client_tool_mcp` WRITE;
INSERT INTO `ai_client_tool_mcp` (`id`, `mcp_id`, `mcp_name`, `transport_type`, `transport_config`, `request_timeout`, `status`, `create_time`, `update_time`)
VALUES
    (1, 'mcp-context7-docs', 'context7-docs', 'stdio', '{\n  "context7-docs": {\n    "command": "npx.cmd",\n    "args": ["-y", "@upstash/context7-mcp@latest"],\n    "env": {\n      "CONTEXT7_API_KEY": "${CONTEXT7_API_KEY:}"\n    },\n    "toolNames": ["resolve-library-id", "query-docs"]\n  }\n}', 3, 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (2, 'mcp-exa-search', 'exa-search', 'streamable_http', '{\n  "baseUri": "https://mcp.exa.ai/mcp?tools=web_search_exa,web_fetch_exa,web_search_advanced_exa",\n  "headers": {\n    "x-api-key": "${EXA_API_KEY:}"\n  },\n  "toolNames": ["exa-search", "web_search_exa", "web_fetch_exa", "web_search_advanced_exa"]\n}', 1, 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
UNLOCK TABLES;

-- 聊天模型配置
DROP TABLE IF EXISTS `ai_client_model`;
CREATE TABLE `ai_client_model` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `model_id` varchar(64) NOT NULL COMMENT '模型业务ID',
    `api_id` varchar(64) NOT NULL COMMENT '关联的模型 API 业务ID',
    `model_name` varchar(64) NOT NULL COMMENT '模型名称',
    `model_type` varchar(32) NOT NULL COMMENT '模型协议类型',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_id` (`model_id`),
    KEY `idx_api_id` (`api_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聊天模型配置表';

LOCK TABLES `ai_client_model` WRITE;
INSERT INTO `ai_client_model` (`id`, `model_id`, `api_id`, `model_name`, `model_type`, `status`, `create_time`, `update_time`)
VALUES
    (1, 'model-qwen37-max', 'api-dashscope-openai', 'qwen3.7-max', 'openai', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
UNLOCK TABLES;

-- 定时任务表
DROP TABLE IF EXISTS `ai_agent_task_schedule`;
CREATE TABLE `ai_agent_task_schedule` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `agent_id` bigint NOT NULL COMMENT '智能体主键ID',
    `task_name` varchar(64) DEFAULT NULL COMMENT '任务名称',
    `description` varchar(255) DEFAULT NULL COMMENT '任务说明',
    `cron_expression` varchar(50) NOT NULL COMMENT 'Cron 表达式',
    `task_param` text COMMENT '任务入参 JSON 或文本',
    `status` tinyint(1) DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体定时任务配置表';

-- 智能体运行主表
DROP TABLE IF EXISTS `ai_agent_run`;
CREATE TABLE `ai_agent_run` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_id` varchar(64) NOT NULL COMMENT '运行ID',
    `agent_id` varchar(64) NOT NULL COMMENT '智能体业务ID',
    `session_id` varchar(128) NOT NULL COMMENT '会话ID',
    `user_message` text COMMENT '用户原始输入',
    `status` varchar(32) NOT NULL COMMENT '运行状态',
    `final_summary` mediumtext COMMENT '最终总结',
    `error_message` varchar(1024) DEFAULT NULL COMMENT '错误信息',
    `cancel_reason` varchar(255) DEFAULT NULL COMMENT '取消原因',
    `session_context_summary` mediumtext COMMENT '执行前注入的 session 短期记忆快照',
    `start_time` datetime DEFAULT NULL COMMENT '开始时间',
    `end_time` datetime DEFAULT NULL COMMENT '结束时间',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_id` (`run_id`),
    KEY `idx_run_session` (`session_id`),
    KEY `idx_run_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体运行主表';

-- Session 级短期记忆消息表
DROP TABLE IF EXISTS `ai_agent_conversation_message`;
CREATE TABLE `ai_agent_conversation_message` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id` varchar(128) NOT NULL COMMENT '会话ID',
    `run_id` varchar(64) NOT NULL COMMENT '运行ID',
    `role` varchar(32) NOT NULL COMMENT '消息角色：USER、ASSISTANT',
    `content` mediumtext NOT NULL COMMENT '用户可见消息原文',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_session_id` (`session_id`, `id`),
    UNIQUE KEY `uk_conversation_run_role` (`run_id`, `role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Session级持久化短期记忆消息表';

-- Session 级结构化摘要和过期状态
DROP TABLE IF EXISTS `ai_agent_conversation_session`;
CREATE TABLE `ai_agent_conversation_session` (
    `session_id` varchar(128) NOT NULL COMMENT '会话ID',
    `summary_json` json NOT NULL COMMENT '目标、约束、决策、未解决问题和回答偏好',
    `summarized_message_id` bigint NOT NULL DEFAULT '0' COMMENT '已滚动进摘要的消息游标',
    `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
    `expires_at` datetime NOT NULL COMMENT '过期时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`session_id`),
    KEY `idx_conversation_session_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Session级短期记忆状态表';

-- 智能体运行步骤表
DROP TABLE IF EXISTS `ai_agent_step_run`;
CREATE TABLE `ai_agent_step_run` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_id` varchar(64) NOT NULL COMMENT '运行ID',
    `step_id` varchar(64) NOT NULL COMMENT '步骤ID',
    `step_name` varchar(255) DEFAULT NULL COMMENT '步骤名称',
    `step_order` int DEFAULT '0' COMMENT '步骤序号',
    `step_type` varchar(32) DEFAULT NULL COMMENT '步骤类型',
    `status` varchar(32) NOT NULL COMMENT '步骤状态',
    `output_summary` mediumtext COMMENT '步骤摘要',
    `error_message` varchar(1024) DEFAULT NULL COMMENT '步骤错误',
    `cost_millis` bigint DEFAULT '0' COMMENT '耗时毫秒',
    `start_time` datetime DEFAULT NULL COMMENT '开始时间',
    `end_time` datetime DEFAULT NULL COMMENT '结束时间',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_step` (`run_id`, `step_id`),
    KEY `idx_step_run_order` (`run_id`, `step_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体运行步骤表';

-- 客户端配置
DROP TABLE IF EXISTS `ai_agent_client_config`;
CREATE TABLE `ai_agent_client_config` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `agent_id` varchar(64) NOT NULL COMMENT '智能体业务ID',
    `client_id` varchar(64) NOT NULL COMMENT '客户端业务ID',
    `client_name` varchar(64) DEFAULT NULL COMMENT '客户端名称',
    `client_type` varchar(64) DEFAULT NULL COMMENT '客户端类型',
    `sequence` int NOT NULL COMMENT '节点顺序',
    `step_prompt` text COMMENT '节点说明',
    `status` int DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_client_seq` (`agent_id`, `client_id`, `sequence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体客户端配置表';

LOCK TABLES `ai_agent_client_config` WRITE;
INSERT INTO `ai_agent_client_config` (`id`, `agent_id`, `client_id`, `client_name`, `client_type`, `sequence`, `step_prompt`, `status`, `create_time`)
VALUES
    (1, 'agent-java-knowledge', 'client-advisor-main', 'Spring AI Advisor 主执行', 'EXECUTOR_CLIENT', 1, '通过 ChatClient 与 Advisor Chain 注入 Session、RAG evidence、只读 MCP 工具并生成最终回答。', 1, '2025-09-01 00:00:00');
UNLOCK TABLES;

-- 客户端、模型、提示词和工具的统一关联配置
DROP TABLE IF EXISTS `ai_client_config`;
CREATE TABLE `ai_client_config` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `source_type` varchar(32) NOT NULL COMMENT '源类型：model 或 client',
    `source_id` varchar(64) NOT NULL COMMENT '源业务ID',
    `target_type` varchar(32) NOT NULL COMMENT '目标类型：model、prompt、tool_mcp、rag',
    `target_id` varchar(64) NOT NULL COMMENT '目标业务ID',
    `ext_param` varchar(1024) DEFAULT NULL COMMENT '扩展参数 JSON',
    `status` tinyint(1) DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_source_id` (`source_id`),
    KEY `idx_target_id` (`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI客户端统一关联配置表';

LOCK TABLES `ai_client_config` WRITE;
INSERT INTO `ai_client_config` (`id`, `source_type`, `source_id`, `target_type`, `target_id`, `ext_param`, `status`, `create_time`, `update_time`)
VALUES
    (1, 'client', 'client-advisor-main', 'model', 'model-qwen37-max', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (2, 'client', 'client-advisor-main', 'prompt', 'prompt-evidence-answer', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (3, 'model', 'model-qwen37-max', 'tool_mcp', 'mcp-context7-docs', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (4, 'model', 'model-qwen37-max', 'tool_mcp', 'mcp-exa-search', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (5, 'client', 'client-advisor-main', 'rag', 'rag-agent-station', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
UNLOCK TABLES;

/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;
/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
