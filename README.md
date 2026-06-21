# AI Agent Station

AI Agent Station 是一个面向企业知识助手、技术资料调研和知识库问答场景的通用智能体平台，当前主线已经从固定工作流收敛为 Controlled Agent Harness。

当前基础版本：

- Spring Boot `4.1.0`
- Spring AI `2.0.0`
- MyBatis Spring Boot Starter `4.0.1`
- JDK `17`
- 默认 Chat 模型：`qwen3.7-max`
- 默认 Embedding 模型：`text-embedding-v4`，维度 `1024`

Spring AI 2 适配遵循当前官方 API：模型连接参数使用不可变 `OpenAiChatOptions`，运行时 MCP 工具通过请求级 `tools(...)` 注入并由 `ChatClient` 的工具调用 Advisor 完成调用循环，MCP SDK 使用 Jackson 3 mapper。详细迁移记录见 [Spring AI 2.0 升级说明](docs/spring-ai-2-upgrade.md)。

## 项目亮点

- 设计 Controlled Agent Harness 执行内核，将单次请求抽象为 `Run -> HarnessContext -> Action -> Observation -> Evaluation -> Final`，通过受控 Action Loop 约束模型行为并避免固定流程膨胀。
- 实现 MCP 运行时工具治理，按 docs/search 场景动态筛选本轮可用工具，并通过只读 evidence 规则、风险分级和调用期兜底避免模型随意调用写入、通知、记忆或命令类工具。
- 收敛 Agentic RAG 3.0 链路，以 `AgenticRagRuntime` 作为唯一主入口，支持检索规划、证据评估、最多一次二次检索、MCP 只读 evidence 融合和真实 `rag_evidence` trace。
- 落地轻量上下文与记忆治理，将同一 session 的用户输入和最终回答持久化，显式区分项目规则、用户偏好、session 历史、当前 Run 输出和压缩摘要。

## 核心链路

一次标准执行链路如下：

1. `AgentDispatchService` 校验智能体状态，并提交异步执行任务。
2. `AgentHarnessExecuteService` 初始化 `runId`、session 短期记忆、上下文边界和运行态记录。
3. `RuntimeToolCapabilityService` 根据用户输入筛选 docs/search 类 MCP 工具，输出 `tool_routing`。
4. `AgentActionParser` 解析模型输出的受控 action JSON，`AgentActionPolicy` 检查最大轮次、上下文预算和只读工具边界。
5. `AgenticRagRuntime` 在 `RAG_RETRIEVE` 动作中完成检索规划、本地检索、MCP 只读资料补充、证据评估、有限二次检索和 grounded answer。
6. Harness 写入 `ai_agent_run / ai_agent_step_run / ai_agent_conversation_message`，并通过流式事件输出 `context_boundary`、`tool_routing`、`harness_observation` 和 `rag_evidence`。

受控 Action 协议：

- `RAG_PLAN`
- `RAG_RETRIEVE`
- `MCP_READ`
- `EVALUATE_EVIDENCE`
- `LLM_RESPOND`
- `ASK_CLARIFY`
- `FINAL`

默认最大 Action Loop 为 `4` 轮，RAG 二次检索最多 `1` 次。

## Agentic RAG

RAG 主链路只走 `AgenticRagRuntime`，不再保留模型调用中的隐式检索 Advisor。

执行过程：

```text
RAG_RETRIEVE
-> query rewrite / intent classify
-> PGVector + BM25 local retrieval
-> optional MCP read-only evidence
-> evidence evaluation
-> optional second retrieval
-> grounded answer
```

当前策略：

- `PGVector` 是默认语义召回通道。
- `BM25` 是可选精确召回通道。
- `RRF` 只在多路结果并存时体现为融合结果。
- `Small-to-Big` 只在检索实现判定需要父块上下文时扩展。
- `rag_evidence` 输出真实 trace，包括检索轮次、query、通道、命中数、是否触发二次检索、最终证据和无证据原因。

## MCP 治理

默认 MCP seed 保留：

- `context7-docs`
- `exa-search`

不再默认保留顺序推理、记忆写入、桌面通知等与当前知识助手场景关系不强的 MCP。

RAG evidence 子链路只允许只读工具名：

- 允许：`search / docs / fetch / read / get / open / list / resolve`
- 禁止：`create / update / write / send / notify / memory / shell`

## 上下文与记忆

当前记忆定位是 session 级短期记忆，不是长期用户画像系统。

- `ai_agent_conversation_message` 只记录用户输入和最终回答。
- 内部 Action Prompt、RAG Prompt 和工具调用过程不写入用户记忆。
- session 历史超预算后，使用“较早消息摘要 + 最近消息原文”的方式组装上下文。
- 当前 Run 的步骤输出超预算后，使用 `history_summary + 最近两个步骤输出` 继续执行。
- 上下文预算使用轻量估算，不宣称精确 tokenizer。

## 模块结构

- `ai-agent-station-domain`：领域模型、Harness Runtime、Agentic RAG、上下文和运行态服务
- `ai-agent-station-infrastructure`：DAO、仓储、模型端口和 MCP 工具装配
- `ai-agent-station-trigger`：HTTP 入口与流式输出
- `ai-agent-station-app`：Spring Boot 启动、PGVector / ES 适配、测试入口
- `docs/dev-ops/nginx/html`：本地演示前端

## 依赖环境

- JDK 17
- Maven 3.8+
- Docker Desktop
- MySQL 8.x
- Docker 容器 PostgreSQL + PGVector
- Docker 容器 Elasticsearch 7.17.x

常用环境变量：

- `OPENAI_API_KEY`：DashScope OpenAI compatible chat / embedding key
- `CONTEXT7_API_KEY`：可选
- `EXA_API_KEY`：可选
- `RUN_REAL_AI_TESTS=true`：手工真实模型测试开关
- `RUN_DB_MUTATION_TESTS=true`：数据库变更类测试开关

## 本地启动

确认本地依赖：

```powershell
.\scripts\dev\up-local-stack.ps1
```

项目默认由 Docker `pgvector` 独占宿主机 `5432`。如果 Windows 本地 PostgreSQL 服务仍在运行，开发脚本会直接报错，避免应用误连到本地空库；需要本地 PostgreSQL 时再手工启动对应服务。

启动应用：

```powershell
.\scripts\dev\start-app-local.ps1
```

脚本会设置：

- `MYSQL_URL=jdbc:mysql://127.0.0.1:3306/ai-agent-station...`
- `PGVECTOR_URL=jdbc:postgresql://127.0.0.1:5432/ai-agent-station`
- `AI_AGENT_ES_BASE_URL=http://127.0.0.1:9200`
- `AI_AGENT_VECTOR_STORE_ENABLED=true`
- `AI_AGENT_VECTOR_STORE_MODEL=text-embedding-v4`
- `AI_AGENT_VECTOR_STORE_DIMENSIONS=1024`

导入 Markdown Parent-Child 知识：

```powershell
.\scripts\dev\import-markdown-rag.ps1
```

执行 smoke：

```powershell
.\scripts\dev\run-local-smoke.ps1
```

## 核心表

- `ai_agent`：智能体配置
- `ai_client*`：Prompt / Model / Advisor / MCP Tool 装配配置
- `ai_agent_harness_config`：Harness 客户端角色配置
- `ai_agent_run / ai_agent_step_run`：运行态追踪
- `ai_agent_conversation_message`：session 短期记忆
- `ai_rag_document / ai_rag_chunk`：RAG Parent-Child 元数据

## 示例请求

```http
POST /api/v1/agent/execute
Content-Type: application/json

{
  "aiAgentId": "1",
  "sessionId": "session_demo_001",
  "message": "请调研 Spring AI MCP Client 的使用方式，并按结论、证据、落地建议输出。",
  "maxStep": 4
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
