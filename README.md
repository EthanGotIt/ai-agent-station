# AI Agent Station

AI Agent Station 是一个基于 Spring AI 和 Spring AI Alibaba 的轻量受控 Agent Runtime。当前主线不再维护自研 JSON Flow Plan，而是使用单 `ReactAgent` GraphRuntime 承载执行、工具调用和 session checkpoint。

当前基础版本：

- Spring Boot `3.5.14`
- Spring AI `1.1.7`
- Spring AI Alibaba `1.1.2.3`
- MCP SDK `0.18.2`
- JDK `17`
- 默认 Chat 模型：`qwen3.7-max`

## 项目亮点

- GraphRuntime：使用 Spring AI Alibaba `ReactAgent` 替换旧 FlowRuntime，统一模型决策、工具调用、Todo 规划和运行终止。
- Session 短期记忆：使用官方 `PostgresSaver` 将同一 `sessionId` 映射为 Graph thread，并持久化 checkpoint；当前不实现跨 session 长期画像。
- 上下文治理：使用官方 `SummarizationHook` 做会话摘要压缩，并通过 `TokenCounter.approximateMsgCounter` 提供可校准的近似 token 估算；不宣传为精确 tokenizer。
- MCP Tool Guard：请求期稳定规则路由 MCP 工具，注入前再次按 allowed set 和风险规则过滤，调用期拒绝越权与危险工具，最终异常统一返回结构化结果。
- MCP 生命周期治理：装配阶段只登记配置，客户端后台并发预热；请求命中路由时按需初始化并复用缓存，初始化超时则本轮回退为无外部工具执行。Actuator health、结构化日志和 `graph_lifecycle` 事件可观测初始化状态与解析耗时。
- MCP 有限重试：仅对低风险、幂等查询类 MCP 工具启用一次有限重试；写操作、通知工具和本地 `rag_search` 不自动重试。
- Agentic RAG：向 GraphRuntime 注入本地 `rag_search` 工具；保留 Query Rewrite、PGVector + Elasticsearch BM25、RRF、Small-to-Big 和 evidence 去重，并输出 `rag_evidence`。
- RAG 降级：PGVector 语义召回异常时记录告警并继续 Elasticsearch BM25，避免单路外部依赖故障中断 Agent 执行。
- 文档导入：使用 Spring AI Alibaba `MarkdownDocumentParser` 替换自定义 Markdown 加载层；针对其 `InputStreamReader` 默认字符集行为增加无损适配，Parent-Child 分块和索引策略保持不变。
- 运行态审计：MySQL `ai_agent_run / ai_agent_step_run` 继续记录运行状态、终止原因和单次 Graph 执行摘要；执行图状态由 PostgreSQL checkpoint 保存。

## 核心链路

```text
HTTP execute
  -> 创建 MySQL Run / Step 审计记录
  -> 读取 ai_agent_runtime_config
  -> 动态路由 MCP 工具并执行 Tool Guard
  -> 组装 ReactAgent
       - PostgresSaver
       - SummarizationHook
       - ModelCallLimitHook / ToolCallLimitHook
       - TodoListInterceptor
       - StructuredToolErrorInterceptor / ToolRetryInterceptor
       - filtered MCP ToolCallback
       - rag_search
  -> sessionId 映射 Graph threadId
  -> ReactAgent 自主决策模型调用、Todo 和工具调用
  -> 输出 summary / complete，回写 MySQL 审计记录
```

保留接口：

- `POST /api/v1/agent/execute`
- `GET /api/v1/agent/run/{runId}`
- `POST /api/v1/agent/run/{runId}/cancel`

主要流式事件：

- `context_boundary`
- `tool_routing`
- `graph_lifecycle`
- `todo_update`
- `rag_evidence`
- `summary`
- `complete`

MCP readiness：

- `GET /actuator/health/mcpClients`

## 记忆边界

- PostgreSQL `GraphThread / GraphCheckpoint` 是 session 短期记忆和可重入上下文的唯一来源。
- MySQL `ai_agent_run / ai_agent_step_run` 是审计记录，不是对话消息表。
- 已删除 `ai_agent_conversation_message` 双写链和自研 `ContextWindowGuard`。
- 已删除不再参与执行的旧 advisor 动态装配；记忆由 Graph checkpoint 管理，RAG 由 `rag_search` 显式触发。
- `SummarizationHook` 在上下文过长时生成摘要并保留最近消息。
- `ai-agent.graph.summarization.chars-per-token` 默认值为 `4`，用于校准官方近似 token 估算。
- Store 型长期记忆、用户画像、跨 session 偏好合并暂不实现。

## RAG 链路

导入阶段：

```text
MarkdownDocumentParser
  -> Parent section
  -> TokenTextSplitter child chunk
  -> MySQL 父子元数据
  -> PGVector / Elasticsearch child 索引
```

检索阶段：

```text
rag_search
  -> Query Rewrite
  -> PGVector + BM25
  -> RRF
  -> Small-to-Big 父块扩展
  -> 父块去重
  -> rag_evidence
```

## 模块结构

- `ai-agent-station-domain`：GraphRuntime、工具治理、RAG 编排和领域模型
- `ai-agent-station-infrastructure`：动态模型解析、MCP 客户端生命周期、ToolCallback、DAO 与仓储
- `ai-agent-station-trigger`：HTTP 入口与 NDJSON 流式输出
- `ai-agent-station-app`：Spring Boot 启动、数据源、PGVector / ES 适配和测试
- `docs/dev-ops/nginx/html`：本地演示前端

## 本地启动

依赖环境：

- MySQL 8.x：`127.0.0.1:3306`
- PostgreSQL + PGVector：`127.0.0.1:5432`
- Elasticsearch 7.17.x：`127.0.0.1:9200`

必要环境变量：

- `OPENAI_API_KEY`

Chat 和向量模型统一使用百炼 OpenAI-compatible 接口。默认 Embedding 模型为 `text-embedding-v4`，维度为 `1024`。

可选 MCP 环境变量：

- `CONTEXT7_API_KEY`
- `EXA_API_KEY`

执行：

```powershell
.\scripts\dev\up-local-stack.ps1
.\scripts\dev\start-app-local.ps1
.\scripts\dev\import-markdown-rag.ps1
.\scripts\dev\run-local-smoke.ps1
```

MySQL 初始化 SQL：

```text
docs/dev-ops/mysql/sql/ai-agent-station.sql
```

PostgreSQL 初始化 SQL：

```text
docs/dev-ops/pgvector/sql/ai-agent-station.sql
```

开发环境中 `PostgresSaver` 允许自动创建 checkpoint 表；生产环境应预建表，并将 saver DDL 策略调整为 `CREATE_NONE`。

## 核心表

MySQL：

- `ai_agent`
- `ai_agent_runtime_config`
- `ai_client*`
- `ai_agent_run`
- `ai_agent_step_run`
- `ai_rag_document`
- `ai_rag_chunk`

PostgreSQL：

- `GraphThread`
- `GraphCheckpoint`
- `vector_store_openai`

## 边界说明

当前实现是单 Agent GraphRuntime，不是完整多 Agent 平台。暂不实现多 Agent handoff、长期 Store、HITL、真实危险工具沙箱、Nacos 配置中心和 Studio 可视化。

详细迁移说明见：

- `docs/agent-runtime-phase8.md`
- `docs/agent-runtime-phase9.md`
- `docs/agent-runtime-phase10.md`
- `docs/smoke.md`
