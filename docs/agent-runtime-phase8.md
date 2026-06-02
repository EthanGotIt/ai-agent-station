# Phase 8：Spring AI Alibaba GraphRuntime 迁移

## 状态

代码迁移、离线兼容测试、完整回归、打包和真实模型 smoke 已完成。向量模型已收敛为百炼 `text-embedding-v4`，Chat 与 Embedding 共用一套 OpenAI-compatible 接入。

## 技术评估

本阶段引入 Spring AI Alibaba `1.1.2.3`，但保持 Spring AI `1.1.7`、Spring Boot `3.5.14` 和 MCP SDK `0.18.2`。

依赖门禁确认：

- Graph Framework 未拉回旧版 Spring AI。
- MCP SDK 保持 `0.18.2`。
- Oracle、MySQL 9、Redis、Mongo 和旧 MCP 传递依赖已排除。
- DeepSeek 和 ZhipuAI 消息兼容模块保留：官方 checkpoint serializer 初始化时会直接注册对应的 AssistantMessage 类型。
- Markdown parser 的 `DocumentParser` API 位于官方 `spring-ai-alibaba-dashscope` 模块，因此显式补充该依赖。

## 代码变化

- 删除旧 `FlowPlanExecuteService`、Step1-6、JSON Plan parser/validator/prompt。
- 删除 `ai_agent_conversation_message` 双写链和自研上下文压缩器。
- 删除未再绑定到 `ChatClient` 的旧 advisor 动态装配子系统，记忆和 RAG 分别收敛到 `PostgresSaver / SummarizationHook` 与 `rag_search`。
- 新增 `GraphAgentExecuteService`，单次请求组装一个官方 `ReactAgent`。
- 新增 PostgreSQL `PostgresSaver`，同一 `sessionId` 映射为稳定 Graph threadId。
- 使用官方 `SummarizationHook`、`ModelCallLimitHook`、`ToolCallLimitHook`、`TodoListInterceptor` 和 `ToolErrorInterceptor`。
- 保留 MCP 动态路由、注入前 Tool Guard 和调用期 `GuardedToolCallback`。
- MCP SDK 传输和初始化已下沉 infrastructure；装配阶段只登记配置，客户端后台并发预热，并在请求命中路由时按需初始化和复用。
- 新增本地 `rag_search` ToolCallback，复用现有 Hybrid RAG 和 evidence 输出。
- PGVector 语义召回异常时记录告警并降级到 BM25，不再让单路外部依赖故障中断整个 RAG 工具。
- Markdown 导入切换为官方 `MarkdownDocumentParser`，Parent-Child 算法不重写。
- MySQL 配置由 `ai_agent_flow_config` 收敛为单行 `ai_agent_runtime_config`。
- cancel 接口先持久化 Run / Step 的 `CANCELLED` 终态，再向 Graph 发出 best-effort interrupt。

## 记忆边界

- `GraphThread / GraphCheckpoint`：session 短期记忆和 checkpoint。
- `SummarizationHook`：上下文过长时摘要压缩，并保留最近消息。
- `ai_agent_run / ai_agent_step_run`：运行审计。
- Store 型长期记忆：暂缓。

## 保持兼容

- 保留 execute、run detail 和 cancel HTTP 接口。
- 保留 NDJSON 流式 envelope。
- 保留 `context_boundary`、`tool_routing`、`rag_evidence`、`summary`、`complete`，新增 `graph_lifecycle` 和 `todo_update`。
- Run detail 中旧上下文摘要字段暂时保留为可空兼容字段，不再写入数据库。

## 删除的冗余

- 旧 Flow 双执行路径。
- 旧 JSON Plan VO、parser、validator 和节点测试。
- 自研 `ContextUnitEstimator / ContextWindowGuard`。
- MySQL `ai_agent_conversation_message`。
- MySQL `ai_agent_flow_config`。
- 旧 Flow 专用 `AgentModelPort`。
- 旧 `AiClientAdvisorNode`、`RagAnswerAdvisor`、advisor DAO 和 MySQL `ai_client_advisor`。
- 绕过平台直连模型和 MCP 的旧手工实验测试，包括旧 Flow 命名、手写循环和测试专用 advisor。

## 未做事项

- 多 Agent handoff、router、supervisor。
- Store 长期记忆和用户画像。
- HITL、沙箱、Nacos、Studio。
- 精确 Qwen tokenizer。

## 验收记录

已通过：

```powershell
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=ReactAgentCompatibilityTest,RuntimeToolCapabilityServiceTest,GraphRagSearchToolCallbackTest,RagParentChildIngestionServiceTest,AgentRunLifecycleVOTest" test
mvn -q "-DskipTests=false" test
mvn -q "-DskipTests" package
git diff --check
```

- 离线目标测试：`13` 个通过。
- 完整回归：`124` 个通过。
- MySQL：`ai_agent_runtime_config` 已存在，旧 Flow、conversation message、advisor 表和旧压缩字段已删除。
- PostgreSQL：`GraphThread / GraphCheckpoint` 已创建。
- 真实模型调用通过：DashScope 模型可自主调用 `write_todos`、`rag_search` 和路由后的 MCP ToolCallback。
- `run-local-smoke.ps1` 通过：GraphRuntime、工具路由、BM25 降级后的 RAG evidence、同 session PostgreSQL checkpoint 均完成验收。
- HTTP cancel smoke 通过：Run 和运行中的 Step 同步落为 `CANCELLED`。

环境限制：

- 百炼 `text-embedding-v4` 的真实 Markdown 重导入和 PGVector 语义召回见最新 smoke 记录。
