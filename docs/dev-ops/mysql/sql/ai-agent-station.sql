-- AI Agent Station MySQL seed data
-- 当前数据保留单 Flow Plan 编排体，并内置通用问答更常用的 MCP 工具配置。

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
    (1, '1', 'Flow Plan 编排体', '结构化计划、运行时工具注入、顺序执行、质量监督与总结输出', 'agent', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
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
    (1, '2101', '工具能力摘要客户端', '读取当前智能体可用 MCP 工具，并为执行阶段提供运行时工具策略上下文', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (2, '2102', '结构化计划客户端', '根据用户目标生成 JSON Plan，要求步骤、依赖和成功标准可校验', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (3, '2103', '计划执行客户端', '按已校验的 Plan 执行步骤，并承担质量监督和最终总结', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
UNLOCK TABLES;

-- 模型 API 配置
DROP TABLE IF EXISTS `ai_client_api`;
CREATE TABLE `ai_client_api` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `api_id` varchar(64) NOT NULL COMMENT '模型接口业务ID',
    `base_url` varchar(255) NOT NULL COMMENT 'API 基础地址',
    `api_key` varchar(255) NOT NULL COMMENT 'API 密钥占位符，运行时从环境变量解析',
    `completions_path` varchar(255) NOT NULL COMMENT '对话补全路径',
    `embeddings_path` varchar(255) NOT NULL COMMENT '向量嵌入路径',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_api_id` (`api_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模型 API 配置表';

LOCK TABLES `ai_client_api` WRITE;
INSERT INTO `ai_client_api` (`id`, `api_id`, `base_url`, `api_key`, `completions_path`, `embeddings_path`, `status`, `create_time`, `update_time`)
VALUES
    (1, '1001', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '${OPENAI_API_KEY}', '/chat/completions', '/embeddings', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
UNLOCK TABLES;

-- 顾问配置
DROP TABLE IF EXISTS `ai_client_advisor`;
CREATE TABLE `ai_client_advisor` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `advisor_id` varchar(64) NOT NULL COMMENT '顾问业务ID',
    `advisor_name` varchar(50) NOT NULL COMMENT '顾问名称',
    `advisor_type` varchar(50) NOT NULL COMMENT '顾问类型',
    `order_num` int DEFAULT '0' COMMENT '执行顺序',
    `ext_param` varchar(2048) DEFAULT NULL COMMENT '扩展参数 JSON',
    `status` tinyint(1) DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_advisor_id` (`advisor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模型顾问配置表';

LOCK TABLES `ai_client_advisor` WRITE;
INSERT INTO `ai_client_advisor` (`id`, `advisor_id`, `advisor_name`, `advisor_type`, `order_num`, `ext_param`, `status`, `create_time`, `update_time`)
VALUES
    (1, '4001', '会话记忆', 'ChatMemory', 1, '{\n  "maxMessages": 30\n}', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (2, '4002', '知识问答增强', 'RagAnswer', 2, '{\n  "topK": 4,\n  "queryRewriteEnabled": true,\n  "maxRewriteQueries": 3,\n  "routeTopK": 8,\n  "deduplicateEnabled": true,\n  "contentFingerprintLength": 180\n}', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
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
    (1, '7001', 'AI Agent Station 知识库', 'ai-agent-station', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
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
    (1, '6001', '工具能力摘要提示词', '你是 Flow Plan 编排体的工具能力整理助手。你的职责是理解当前可用 MCP 工具的用途和限制，为执行阶段的运行时工具注入提供简洁、准确的策略上下文。', '工具能力摘要', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (2, '6002', '结构化计划生成提示词', '你是 Flow Plan 编排体的计划生成器。请根据用户目标生成结构化 JSON Plan，计划只描述目标、步骤、依赖关系和成功标准，不输出 toolName 字段，也不提前绑定具体 MCP 工具。除 JSON 外不要输出额外解释。', 'JSON Plan 生成', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (3, '6003', '步骤执行与总结提示词', '你是 Flow Plan 编排体的执行助手。请严格依据已校验的 Plan 执行当前步骤；可用工具会由系统在执行阶段按权限注入，工具失败时不得编造结果，应说明失败并给出替代方案。输出要聚焦结果、过程可追踪，质量检查和最终总结要直接回应用户原始目标。', '步骤执行、质量监督、最终总结', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
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
    (1, '5001', 'context7-docs', 'stdio', '{\n  "context7-docs": {\n    "command": "npx.cmd",\n    "args": ["-y", "@upstash/context7-mcp@latest"],\n    "env": {\n      "CONTEXT7_API_KEY": "${CONTEXT7_API_KEY:}"\n    },\n    "toolNames": ["context7-docs", "resolve-library-id", "get-library-docs"]\n  }\n}', 3, 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (2, '5002', 'exa-search', 'streamable_http', '{\n  "baseUri": "https://mcp.exa.ai/mcp?tools=web_search_exa,web_fetch_exa,web_search_advanced_exa",\n  "headers": {\n    "x-api-key": "${EXA_API_KEY:}"\n  },\n  "toolNames": ["exa-search", "web_search_exa", "web_fetch_exa", "web_search_advanced_exa"]\n}', 1, 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (3, '5003', 'sequential-thinking', 'stdio', '{\n  "sequential-thinking": {\n    "command": "npx.cmd",\n    "args": ["-y", "@modelcontextprotocol/server-sequential-thinking"],\n    "toolNames": ["sequential-thinking", "sequential_thinking"]\n  }\n}', 3, 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (4, '5004', 'memory', 'stdio', '{\n  "memory": {\n    "command": "npx.cmd",\n    "args": ["-y", "@modelcontextprotocol/server-memory"],\n    "env": {\n      "MEMORY_FILE_PATH": "${AI_AGENT_MEMORY_FILE:./data/mcp-memory.jsonl}"\n    },\n    "toolNames": ["memory", "read_graph", "search_nodes", "open_nodes", "create_entities", "create_relations", "add_observations"]\n  }\n}', 3, 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (5, '5005', 'windows-notify', 'stdio', '{\n  "windows-notify": {\n    "command": "npx.cmd",\n    "args": ["-y", "mcp-windows-notify"],\n    "toolNames": ["windows-notify", "send_notification", "notify_task_complete", "notify_error", "notify_reminder"]\n  }\n}', 3, 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
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
    (1, '2001', '1001', 'qwen3.7-max', 'openai', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
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
    `context_original_chars` int DEFAULT '0' COMMENT '压缩前上下文长度',
    `context_compressed_chars` int DEFAULT '0' COMMENT '压缩后上下文长度',
    `context_summary` mediumtext COMMENT '历史摘要',
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
    `content_summary` text COMMENT '轻量摘要，用于超预算压缩',
    `context_units` int DEFAULT '0' COMMENT '轻量上下文预算估算值',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_session_id` (`session_id`, `id`),
    UNIQUE KEY `uk_conversation_run_role` (`run_id`, `role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Session级持久化短期记忆消息表';

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

-- Flow 节点配置
DROP TABLE IF EXISTS `ai_agent_flow_config`;
CREATE TABLE `ai_agent_flow_config` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `agent_id` varchar(64) NOT NULL COMMENT '智能体业务ID',
    `client_id` varchar(64) NOT NULL COMMENT '客户端业务ID',
    `client_name` varchar(64) DEFAULT NULL COMMENT 'Flow 节点名称',
    `client_type` varchar(64) DEFAULT NULL COMMENT 'Flow 节点类型',
    `sequence` int NOT NULL COMMENT '节点顺序',
    `step_prompt` text COMMENT '节点说明',
    `status` int DEFAULT '1' COMMENT '状态：0禁用，1启用',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_client_seq` (`agent_id`, `client_id`, `sequence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体 Flow 节点配置表';

LOCK TABLES `ai_agent_flow_config` WRITE;
INSERT INTO `ai_agent_flow_config` (`id`, `agent_id`, `client_id`, `client_name`, `client_type`, `sequence`, `step_prompt`, `status`, `create_time`)
VALUES
    (1, '1', '2101', '工具能力摘要', 'TOOL_MCP_CLIENT', 1, '从客户端关联模型加载可用 MCP 工具，生成执行阶段工具策略上下文。', 1, '2025-09-01 00:00:00'),
    (2, '1', '2102', '结构化计划生成', 'PLANNING_CLIENT', 2, '根据用户目标生成 JSON Plan，计划阶段不输出 toolName，也不提前绑定具体 MCP 工具。', 1, '2025-09-01 00:00:00'),
    (3, '1', '2103', '步骤执行', 'EXECUTOR_CLIENT', 3, '按已校验的 Plan 顺序执行每个步骤。', 1, '2025-09-01 00:00:00'),
    (4, '1', '2103', '质量监督', 'QUALITY_SUPERVISOR_CLIENT', 4, '检查步骤结果是否满足用户目标和成功标准。', 1, '2025-09-01 00:00:00'),
    (5, '1', '2103', '总结输出', 'RESPONSE_ASSISTANT', 5, '汇总计划、执行记录和监督结果，生成最终回答。', 1, '2025-09-01 00:00:00');
UNLOCK TABLES;

-- 客户端、模型、提示词、顾问和工具的统一关联配置
DROP TABLE IF EXISTS `ai_client_config`;
CREATE TABLE `ai_client_config` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `source_type` varchar(32) NOT NULL COMMENT '源类型：model 或 client',
    `source_id` varchar(64) NOT NULL COMMENT '源业务ID',
    `target_type` varchar(32) NOT NULL COMMENT '目标类型：model、prompt、advisor、tool_mcp',
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
    (1, 'client', '2101', 'model', '2001', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (2, 'client', '2101', 'prompt', '6001', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (3, 'client', '2101', 'advisor', '4001', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (4, 'client', '2102', 'model', '2001', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (5, 'client', '2102', 'prompt', '6002', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (6, 'client', '2102', 'advisor', '4001', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (7, 'client', '2103', 'model', '2001', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (8, 'client', '2103', 'prompt', '6003', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (9, 'client', '2103', 'advisor', '4001', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (10, 'client', '2103', 'advisor', '4002', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (11, 'model', '2001', 'tool_mcp', '5001', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (12, 'model', '2001', 'tool_mcp', '5002', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (13, 'model', '2001', 'tool_mcp', '5003', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (14, 'model', '2001', 'tool_mcp', '5004', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00'),
    (15, 'model', '2001', 'tool_mcp', '5005', '""', 1, '2025-09-01 00:00:00', '2025-09-01 00:00:00');
UNLOCK TABLES;

/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;
/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
