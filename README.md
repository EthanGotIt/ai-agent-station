# AI Agent Station

AI Agent Station 是一个面向企业场景的通用智能体编排平台，当前主线聚焦在：

- Flow Plan 结构化任务编排
- MCP 动态工具路由
- Hybrid RAG 检索增强
- 运行态追踪与上下文保护

## 核心链路

一次标准执行链路如下：

1. `Step1ToolCapabilityNode` 汇总本轮可用工具，输出 `tool_routing`
2. `Step2PlanGenerateNode` 生成 JSON Plan DSL
3. `Step3PlanValidateNode` 做步骤、依赖和工具白名单校验
4. `Step4PlanExecuteNode` 顺序执行计划，并输出 `context_guard`、`rag_evidence`
5. `Step5QualitySupervisorNode` 做质量监督
6. `Step6SummaryNode` 汇总最终结果并写入运行态

RAG 链路当前为：

- Query Rewrite
- PGVector 语义召回 + Elasticsearch BM25 关键词召回
- RRF 融合排序
- Parent-Child Small-to-Big 父块回查
- `rag_evidence` 证据输出

## 模块结构

- `ai-agent-station-domain`：领域模型、Flow 执行链、运行态与导入服务
- `ai-agent-station-infrastructure`：DAO、仓储与数据访问实现
- `ai-agent-station-trigger`：HTTP 入口与流式输出
- `ai-agent-station-app`：Spring Boot 启动、PgVector / ES 适配、测试入口
- `docs/dev-ops/nginx/html`：本地演示前端

## 依赖环境

- JDK 17
- Maven 3.8+
- Docker Desktop
- MySQL 8.x
- PostgreSQL + PGVector
- Elasticsearch 7.17.x

可选环境变量：

- `OPENAI_API_KEY`
- `CONTEXT7_API_KEY`
- `EXA_API_KEY`
- `RUN_REAL_AI_TESTS=true`
- `RUN_DB_MUTATION_TESTS=true`

## 本地启动方式（Docker Desktop）

### 1. 拉起本地依赖

默认只启动核心依赖：MySQL、PGVector、Elasticsearch。

```powershell
.\scripts\dev\up-local-stack.ps1
```

如需 CloudBeaver / Kibana / RedisInsight，再显式开启：

```powershell
.\scripts\dev\up-local-stack.ps1 -WithTools
.\scripts\dev\up-local-stack.ps1 -WithExtras
```

当前统一使用的 compose 为：

- `docs/dev-ops/docker-compose-local.yml`

其中：

- MySQL：`127.0.0.1:13306`
- PGVector：`127.0.0.1:15432`
- Elasticsearch：`127.0.0.1:19200`

### 2. 配置必要环境变量

至少需要：

- `OPENAI_API_KEY`

可选：

- `CONTEXT7_API_KEY`
- `EXA_API_KEY`

### 3. 启动 Spring Boot

推荐直接使用脚本写入本地环境变量并启动：

```powershell
.\scripts\dev\start-app-local.ps1
```

这个脚本会自动设置：

- `MYSQL_URL=jdbc:mysql://127.0.0.1:13306/ai-agent-station...`
- `PGVECTOR_URL=jdbc:postgresql://127.0.0.1:15432/ai-agent-station`
- `AI_AGENT_ES_BASE_URL=http://127.0.0.1:19200`
- `AI_AGENT_VECTOR_STORE_ENABLED=true`

### 4. 导入 Markdown Parent-Child 知识

```powershell
.\scripts\dev\import-markdown-rag.ps1
```

该步骤会使用内置示例 Markdown：

- `spring-ai-mcp-client.md`
- `rag-parent-child-upgrade.md`

导入链路为：

- Markdown 标题分段生成父块
- 父块内部复用 `TokenTextSplitter` 生成子块
- MySQL 写入 `ai_rag_document / ai_rag_chunk`
- PGVector / Elasticsearch 仅索引 child chunk

### 5. 执行本地 smoke

```powershell
.\scripts\dev\run-local-smoke.ps1
```

该脚本会校验：

- Flow Plan 任务能返回 `complete`
- 工具调研任务能出现 `tool_routing`
- RAG 任务能出现 `rag_evidence`
- 运行态表与 RAG 表有对应记录
- PGVector / Elasticsearch 中仅存在 child chunk 索引文档

停止本地依赖：

```powershell
.\scripts\dev\down-local-stack.ps1
```

## 核心表

- `ai_agent`：智能体配置
- `ai_client*`：Prompt / Model / Advisor / MCP Tool 装配配置
- `ai_agent_run` / `ai_agent_step_run`：运行态追踪
- `ai_rag_document`：RAG 文档主表
- `ai_rag_chunk`：RAG Parent-Child 分块表

## 导入与检索说明

当前导入侧支持 Markdown Parent-Child：

- 标题分段生成父块
- 父块内部复用 `TokenTextSplitter` 生成子块
- MySQL 存父子元数据
- PGVector / ES 仅索引子块

查询阶段先命中子块，再按 `parent_chunk_id` 回查父块，用更完整的章节内容参与回答。

## 默认停止的辅助容器

为了避免本地开发阶段无关容器长期占用内存，以下组件默认不会启动，只有显式指定 profile 时才会启动：

- `CloudBeaver`
- `Kibana`
- `RedisInsight`
- `Redis`

项目主链路仅依赖：

- MySQL
- PGVector
- Elasticsearch

## 示例请求

执行入口：

```http
POST /api/v1/agent/execute
Content-Type: application/json

{
  "aiAgentId": "1",
  "sessionId": "session_demo_001",
  "message": "请调研 Spring AI MCP Client 的使用方式，并按结论、证据、落地建议输出。",
  "maxStep": 3
}
```

运行态查询：

```http
GET /api/v1/agent/run/{runId}
```

运行取消：

```http
POST /api/v1/agent/run/{runId}/cancel
```
