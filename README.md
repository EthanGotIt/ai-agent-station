# AI Agent Station

AI Agent Station 是面向企业 Java 项目知识与技术资料调研的 Spring AI 2 智能体执行平台。系统以 `ChatClient` 为模型调用主入口，通过 Advisor Chain 编排上下文预算、Session 记忆、RAG evidence、MCP Tool Calling 和运行态观测，在内部项目知识、官方文档和外部资料之间生成可追踪回答。

## 技术基线

- Spring Boot `4.1.0`、Spring AI `2.0.0`、JDK `17`
- MyBatis、MySQL、PostgreSQL + PGVector、可选 Elasticsearch
- Spring AI MCP Client，支持 Stdio 和 Streamable HTTP
- Spring AI Community `spring-ai-session`，用于 Session 语义和后续压缩策略迁移
- Chat：`qwen3.7-max`
- Embedding：`text-embedding-v4`，`1024` 维

Spring AI 2 迁移细节见 [docs/spring-ai-2-upgrade.md](docs/spring-ai-2-upgrade.md)。

## 核心设计

### Spring AI 2 主执行链路

当前主链路为：

```text
AiAgentController
-> AgentDispatchService
-> SpringAiAgentRuntime
-> Spring AI ChatClient
-> Advisor Chain
-> Tool Calling / RAG Evidence / Session Memory / Trace
```

旧 Harness 代码已作为迁移期兼容路径保留，但不再作为新增能力入口。项目保留的是 Harness 里真正有价值的治理能力，例如证据策略、引用校验、证据不足拒答、运行态复盘和 SSE 可观测性，而不是继续维护一套模型 Action Loop。

Advisor Chain 顺序固定：

1. `ContextBudgetAdvisor`：估算本轮 prompt context units，超过阈值时拒绝新模型调用。
2. `SessionMemoryAdvisor`：接入 Spring AI Community `spring-ai-session`，按 `sessionId` 注入短期上下文。
3. `EvidenceRetrievalAdvisor`：按 `ragId` 范围检索项目知识并格式化 evidence。
4. Spring AI Tool Calling：由 Spring AI 2 驱动工具调用循环，项目只保留工具注册边界和安全包装。
5. `ObservationTraceAdvisor`：提取 RAG metadata、MCP 工具调用记录和 evidence trace，发布运行态事件。

### Adaptive Agentic Retrieval

生产主链路的项目知识检索进入 `EvidenceRetrievalAdvisor`，底层复用项目已有本地 evidence 检索能力。

```text
用户问题
-> Spring AI Advisor Chain
-> EvidenceRetrievalAdvisor
   -> PROJECT_KNOWLEDGE: PGVector 默认，精确术语或语义无结果时启用 BM25
   -> OFFICIAL_DOCS / WEB_RESEARCH: 由 Spring AI Tool Calling 调用只读 MCP 工具补充 evidence
-> ObservationTraceAdvisor 归一化 evidence trace
-> 最终回答使用 [E1] 等引用
```

- PGVector 和 Elasticsearch 都携带允许的 `ragId`，禁止跨知识库召回。
- RRF 只在向量和关键词两个通道同时命中时执行。
- Small-to-Big 只在 evidence gap 包含 `CONTEXT_INCOMPLETE` 时触发。
- 外部 MCP 结果从真实 ToolCallback 调用记录归一化，模型整理文本不会冒充 evidence。
- 无 URI 的 MCP 文本只能作为低可信补充，不能独立满足事实问题的证据充分条件。
- 引用失败最多纠正一次，再失败则返回证据不足。

项目对外称“受控 Agentic RAG 证据闭环”，不把“Agentic RAG 3.0”宣传为行业标准。

### MCP 治理

默认 MCP seed：

- `context7-docs`
- `exa-search`

MCP 不再使用项目自定义关键词打分路由。工具发现、动态加载和工具调用循环交给 Spring AI 2 Tool Calling 与 MCP 集成，项目只保留“注册边界 + 只读过滤 + GuardedToolCallback”三层安全治理。

- 允许：`search / docs / fetch / read / get / open / list / resolve`
- 禁止：`create / update / write / send / notify / memory / shell`

治理边界为“当前 Agent 注册工具集合、只读工具注册边界、调用期 GuardedToolCallback”。

### Session 短期记忆

- 主链路已接入 Spring AI Community `SessionMemoryAdvisor`，通过 `sessionId` 和 `userId` 在 Advisor Chain 中注入会话上下文。
- 现阶段默认使用 `InMemorySessionRepository` 验证 Session 语义，现有数据库 conversation 表继续承担跨重启兜底。
- 后续 JDBC Session 存储会单独迁移，不在本轮为了接入框架强行改表。
- `ai_agent_conversation_message` 只保存用户输入和最终回答原文。
- 只有同时存在 USER 和 ASSISTANT 的成功完整 Turn 会进入后续 Prompt，失败或取消 Run 的孤立 USER 不会被注入。
- `ai_agent_conversation_session` 保存结构化滚动摘要、消息游标、乐观锁版本和 30 天过期时间。
- 上下文使用“结构化摘要 + 最近四个完整 Turn”，预算不足时淘汰整个旧 Turn，不截断单条消息。
- 摘要只保存目标、约束、用户确认的决策、未解决问题和回答偏好，不保存工具输出、外部事实或模型猜测。
- 清除接口 `DELETE /api/v1/agent/session/{sessionId}/memory` 只在 `dev` Profile 注册。

这仍是 Session 短期记忆，不是长期用户画像或向量记忆系统。

### 上下文预算

上下文按单次模型调用组装，不累计多次模型输入输出。`ContextBudgetAdvisor` 在 Advisor Chain 最前面执行，估算本轮 prompt 的 context units 并在达到停止阈值时拒绝新模型调用。

配置项为 `ai-agent.context.max-context-units`。估算器是中英文 heuristic，不是精确 tokenizer。

## 运行态与事件

执行记录保存在：

- `ai_agent_run`
- `ai_agent_step_run`

主要 NDJSON 事件：

- `context_boundary`
- `agent_observation`
- `harness_observation`，迁移期兼容别名
- `rag_evidence`
- `summary`
- `complete`

`rag_evidence` 包含决策轮次、知识范围、实际通道、来源、Evidence Policy 结果、耗时和最终 Evidence ID，不输出完整工具原始结果。

## 测试与评测

默认单测不启动数据库、PGVector、Elasticsearch、MCP 子进程或外部模型：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q "-DskipTests=false" test
```

DAO、PGVector、ES、MCP 和真实模型验证使用 `*IT` 与 `integration` Profile 显式执行：

```powershell
mvn -q -Pintegration "-DskipTests=false" verify
```

60 条冻结评测集位于 `ai-agent-station-app/src/test/resources/evaluation/rag-evaluation-v1.jsonl`，评测规则见 [docs/evaluation/rag-evaluation-v1.md](docs/evaluation/rag-evaluation-v1.md)。日常修改只跑 quick live 抽样回归，阶段验收或效果结论才跑 full live 全量评测。

```powershell
.\scripts\dev\run-live-rag-evaluation.ps1
.\scripts\dev\run-live-rag-evaluation.ps1 -Profile full
.\scripts\dev\run-live-rag-evaluation.ps1 -Profile custom -CaseIds 'PS04,ET01,NR01'
```

在 full live evaluation 产生真实报告前，不宣称 BM25、RRF、Small-to-Big 或二次检索带来确定数值提升。

当前默认回归已覆盖 Spring AI 2 兼容性、社区 Session API、Advisor 基础设施、RAG 入库、会话记忆和上下文预算。完整外部 MCP evidence、三组 RAG 对照和消融结论仍需 live evaluation 单独验收，详见升级总控文档。

## 本地运行

```powershell
.\scripts\dev\up-local-stack.ps1
.\scripts\dev\start-app-local.ps1
.\scripts\dev\import-markdown-rag.ps1
.\scripts\dev\run-local-smoke.ps1
```

必要环境变量：

- `OPENAI_API_KEY`：DashScope OpenAI-compatible Chat/Embedding key
- `CONTEXT7_API_KEY`：可选
- `EXA_API_KEY`：可选

示例请求：

```http
POST /api/v1/agent/execute
Content-Type: application/json

{
  "aiAgentId": "1",
  "sessionId": "session_demo_001",
  "message": "请核验 Spring AI toolContext 的官方用法，并说明当前项目如何落地。",
  "maxStep": 4
}
```

`maxStep` 仅作为 `2-4` 轮软上限，服务端硬上限始终优先。
