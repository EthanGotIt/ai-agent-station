# AI Agent Station

AI Agent Station 是一个面向企业场景的通用智能体编排平台，当前主线聚焦在：

- Flow Plan 结构化任务编排
- MCP 动态工具路由与 Tool Guard 治理
- Agentic RAG 检索增强
- 运行态追踪与上下文保护
- 持久化 Session 短期记忆与上下文治理

当前基础版本：

- Spring Boot `3.5.14`
- Spring AI `1.1.7`
- JDK `17`
- 默认 Chat 模型：`qwen3.7-max`

## 项目亮点

- 轻量 Agent Runtime：将执行过程收敛为 `Run -> Plan -> Step -> Result`，支持运行状态、步骤状态、失败原因、取消/跳过原因和上下文压缩摘要复盘。
- Tool Guard 治理：在 MCP 动态工具路由基础上加入风险分级、运行时工具筛选、危险工具拦截和工具异常归一化。
- Agentic RAG：将检索显式纳入 Flow Plan `RAG` 步骤，并输出 `rag_evidence`，让 Query Rewrite、混合召回、RRF、Small-to-Big 和证据去重可追踪。
- 轻量记忆治理：将同一 session 的用户输入和最终回答持久化到 `ai_agent_conversation_message`，显式区分项目规则、session 历史、当前 Run 步骤输出和压缩摘要；服务重启后可恢复短期会话上下文，但不夸大为跨 session 长期记忆。

## 升级后的代码健康评估

本项目当前更适合定义为“基于 Spring AI 的轻量受控 Agent Runtime”，不是完整多 Agent 框架、长期记忆系统或沙箱级生产平台。升级 Spring Boot / Spring AI 后，优先处理了和新版本直接相关的兼容点：

- Spring AI 记忆 Advisor：`PromptChatMemoryAdvisor` 已替换为 `MessageChatMemoryAdvisor`，避免继续依赖已废弃并标记移除的 API。
- MCP SDK 适配：新版 MCP SDK 不再提供 `McpJsonMapper.getDefault()`，stdio MCP transport 已统一改为 `JacksonMcpJsonMapper`。
- MCP 初始化治理：数据库 `request_timeout` 同时约束请求和初始化握手，避免 npm stdio server 冷启动较慢时落回 SDK 默认初始化窗口。
- Logback 配置：升级到 Logback 1.5 兼容写法，去掉旧的 `converterClass` 和 `SizeAndTimeBasedFNATP` 配置。
- Maven 构建：编译插件版本交由 Spring Boot parent 管理，Java 17 编译改用 `release`，运行参数移除 Java 8 时代的过期 JVM 参数。

当前仍有一些可接受的工程冗余，属于后续维护项，不建议在收口阶段继续大拆：

- `AiAgentController` 的 DTO 手工映射已经偏多，如果运行详情继续扩展，再抽 mapper。
- `Step4PlanExecuteNode` 仍是计划执行主节点，虽然 RAG evidence 已拆到 `RagEvidenceAssembler`，但恢复/重试/事件总线没有必要现在引入。
- `AgentPlanPromptFactory` 的 prompt section 继续变长，后续可按 section builder 拆分。
- `ToolGuardPolicy` 当前按工具名启发式分级，接入更多真实工具后再改为配置化策略。
- `SessionContextAssembler` 当前使用固定的最近 20 条加载上限、最近 4 条原文保留数量和 heuristic 预算；真实流量验证后再配置化。
- `ai_agent_conversation_message` 当前没有归档和过期清理任务；如果进入长期运行环境，需要增加 session 消息保留周期。
- 手工 AI 测试仍包含较多演示型 prompt 和外部服务依赖，适合保留为 smoke/演示材料，不适合作为核心单测膨胀点。

## 核心链路

一次标准执行链路如下：

1. `Step1ToolCapabilityNode` 汇总本轮可用工具，经过 Tool Guard 后输出 `tool_routing`
2. `Step2PlanGenerateNode` 生成 JSON Plan DSL
3. `Step3PlanValidateNode` 做步骤、依赖和类型校验
4. `Step4PlanExecuteNode` 顺序执行计划，为 `LLM / TOOL` 步骤注入本轮筛选后的 MCP ToolCallback，并输出 `context_guard`、`rag_evidence`
5. `Step5QualitySupervisorNode` 做质量监督
6. `Step6SummaryNode` 汇总最终结果并写入运行态与 session 短期记忆

## Agent Runtime 执行生命周期

Phase 1 将原有 Flow 执行链路收敛为轻量 Agent Runtime 视图：

```text
Run -> Plan -> Step -> Result
```

- `Run`：一次用户请求对应一个 `runId`，记录 `INIT / RUNNING / SUCCESS / FAILED / CANCELLED` 状态。
- `Plan`：模型生成 JSON Plan 后，先经过步骤数、依赖关系和步骤类型校验；具体工具不在计划阶段绑定。
- `Step`：每个系统节点和计划步骤都会写入 `ai_agent_step_run`，包含状态、耗时、错误或跳过原因。
- `Result`：最终总结、失败原因、取消原因、执行前 session 记忆快照和当前 Run 上下文压缩摘要会分别回写到 `ai_agent_run`。

运行详情接口 `GET /api/v1/agent/run/{runId}` 会返回 `lifecycle` 视图，用于复盘：

- `runtimePhase`：`PLANNING / VALIDATING / EXECUTING / SUPERVISING / SUMMARIZING / COMPLETED / FAILED / CANCELLED`
- `currentStepId`：当前正在执行的步骤
- `terminalReason`：失败或取消时的终止原因
- `trackedStepCount`、`completedStepCount`、`failedStepCount`、`skippedStepCount`、`cancelledStepCount`
- `contextCompacted`：本次运行是否触发上下文压缩

Phase 4 补充了上下文治理边界：

- `context_boundary`：流式事件中输出本轮 `sessionId`、项目规则作用域、用户偏好作用域、持久化会话上下文作用域和 Run 摘要。
- `contextBoundary`：运行详情接口返回同样的边界信息，便于复盘是否发生跨 session 串记忆。
- 用户偏好只从当前请求轻量识别，并按 session 标记作用域；当前不写入长期记忆。
- session 历史超预算后，`SessionContextAssembler` 使用“较早消息摘要 + 最近 4 条消息原文”继续组装上下文。
- 当前 Run 的 context guard 首次压缩后，后续步骤继续使用 `history_summary + 最近 2 个步骤输出`，避免把完整步骤输出重新塞回提示词。
- 只持久化用户输入和最终回答，不把 Planner、Executor、Supervisor 的内部 prompt 写入用户记忆。
- Flow Runtime 内部模型调用过滤 `MessageChatMemoryAdvisor`，避免内部 prompt 污染内存窗口；通用组件装配仍保留 Spring AI ChatMemory Advisor 配置。
- 上下文预算借鉴 token count estimator 的抽象方式，当前使用轻量 `ContextUnitEstimator` 估算，不绑定真实模型 tokenizer。

详细设计见 `docs/agent-runtime-phase7.md`。

RAG 链路当前为：

- Query Rewrite
- PGVector 语义召回 + Elasticsearch BM25 关键词召回
- RRF 融合排序
- Parent-Child Small-to-Big 父块回查
- Flow Plan `RAG` 步骤
- `rag_evidence` 结构化证据输出

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
- `JINA_API_KEY`
- `CONTEXT7_API_KEY`
- `EXA_API_KEY`
- `RUN_REAL_AI_TESTS=true`
- `RUN_DB_MUTATION_TESTS=true`

## 本地启动方式（复用现有环境）

### 1. 准备现有依赖

当前默认复用你机器上已有的本地环境：

- MySQL：`127.0.0.1:3306`
- PGVector：`127.0.0.1:5432`
- Elasticsearch：`127.0.0.1:9200`

其中：

- MySQL 走本机已有实例
- PGVector / Elasticsearch 走 Docker Desktop 中已有容器

如需快速确认环境可用，可以执行：

```powershell
.\scripts\dev\up-local-stack.ps1
```

该脚本不会再新建隔离 stack，只会：

- 检查本机 MySQL `3306`
- 启动并检查现有 `pgvector`
- 启动并检查现有 `elasticsearch`

### 2. 配置必要环境变量

至少需要：

- `OPENAI_API_KEY`
- `JINA_API_KEY`

可选：

- `CONTEXT7_API_KEY`
- `EXA_API_KEY`

### 3. 启动 Spring Boot

推荐直接使用脚本写入本地环境变量并启动：

```powershell
.\scripts\dev\start-app-local.ps1
```

这个脚本会自动设置：

- `MYSQL_URL=jdbc:mysql://127.0.0.1:3306/ai-agent-station...`
- `PGVECTOR_URL=jdbc:postgresql://127.0.0.1:5432/ai-agent-station`
- `AI_AGENT_ES_BASE_URL=http://127.0.0.1:9200`
- `AI_AGENT_VECTOR_STORE_ENABLED=true`
- `AI_AGENT_VECTOR_STORE_MODEL=jina-embeddings-v5-text-small`
- `AI_AGENT_VECTOR_STORE_DIMENSIONS=1024`

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
- 上下文治理任务能出现 `context_boundary`
- 运行态表与 RAG 表有对应记录
- PGVector / Elasticsearch 中仅存在 child chunk 索引文档

停止本地依赖：

```powershell
.\scripts\dev\down-local-stack.ps1
```

如需顺手关闭观测类容器：

```powershell
.\scripts\dev\down-local-stack.ps1 -StopObservability
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

当前本地默认向量模型为：

- `jina-embeddings-v5-text-small`
- 维度 `1024`
- Chat 侧走 DashScope OpenAI compatible 模式，默认模型配置为 `qwen3.7-max`

## 默认停止的辅助容器

项目主链路仅依赖：

- MySQL
- PGVector
- Elasticsearch

本地如无需观测类容器，建议保持以下服务停止，减少内存占用：

- `kibana`
- `logstash`
- `grafana`
- `prometheus`

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

运行详情会包含：

- `lifecycle`：运行阶段、步骤计数、终止原因、上下文压缩标记
- `contextBoundary`：本轮持久化短期记忆、用户偏好、项目规则和 Run 摘要的隔离边界

运行取消：

```http
POST /api/v1/agent/run/{runId}/cancel
```
