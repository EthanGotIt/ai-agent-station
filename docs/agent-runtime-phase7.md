# Agent Runtime Phase 7：持久化 Session 短期记忆

> 历史阶段记录：当前执行内核已在 Phase 8 替换为 Spring AI Alibaba ReactAgent GraphRuntime，MySQL 消息双写已删除。

## 状态

状态：已完成。代码、SQL seed、本地 MySQL 结构同步、目标单测、完整回归和真实双轮 API smoke 已完成。

目标单测：`SessionContextAssemblerTest`、`AgentConversationMemoryServiceTest`、`AgentContextBoundaryServiceTest`、`AgentContextWindowServiceTest`、`AiAgentConversationMessageDaoTest`、`AgentRuntimeAdvisorPolicyTest`，共 17 个通过。

## 技术评估

本阶段继续沿用 Spring AI + Flow Plan Runtime + MyBatis，不引入 OpenAI Agents SDK、LangGraph、CrewAI，也不接真实 tokenizer。

原因是当前缺口不是复杂图编排，而是同一 session 跨请求、跨重启的短期上下文恢复。新增独立消息表、仓储和上下文组装器即可补齐，不需要迁移执行模型。

## 记忆边界

新增 `ai_agent_conversation_message`：

- 只记录 USER 输入和 ASSISTANT 最终回答。
- 使用 `(run_id, role)` 唯一约束避免同一 Run 重复写入同一角色消息。
- 不记录 Planner、Executor、Supervisor 内部 prompt。
- 不保存跨 session 用户画像，不做自动偏好沉淀。
- 空 `sessionId` 不持久化，避免匿名请求共享历史。

新增 `AgentConversationMemoryService`：

- Run 创建后记录 USER 输入。
- 最终总结成功后记录 ASSISTANT 回答。
- 记忆表读写失败时降级为空历史或跳过写入，不阻断 Agent Run。

新增 `AgentRuntimeAdvisorPolicy`：

- Flow Runtime 内部模型调用过滤 `MessageChatMemoryAdvisor`，避免 Planner、Executor、Supervisor、Summary prompt 污染内存窗口。
- Spring AI ChatMemory Advisor 仍作为通用可配置组件保留；Flow Runtime 使用显式持久化 session context。

新上下文注入链路：

```text
User Request
  -> load prior session recent messages
  -> assemble session_context_summary
  -> create Agent Run
  -> write conversation_message(role=user)
  -> Flow Plan Run
  -> compress current run step outputs when needed
  -> final summary
  -> write conversation_message(role=assistant)
```

Prompt 中的上下文来源明确分为三类：

- `project_rules`：固定项目规则，所有 session 共享。
- `session_context`：同一 session 的历史用户输入、最终回答和摘要。
- `run_context`：当前 Run 内的 step outputs、`history_summary` 和 RAG evidence。

运行态表中进一步区分：

- `ai_agent_run.session_context_summary`：执行开始前注入的 session 短期记忆快照。
- `ai_agent_run.context_summary`：当前 Run 内 step outputs 压缩后生成的摘要。
- `GET /api/v1/agent/run/{runId}` 分别映射为 `contextBoundary.sessionContextSummary` 和 `contextBoundary.runContextSummary`，避免把两类摘要混为一谈。

新增 `SessionContextAssembler`：

- 默认读取同一 session 最近 20 条消息。
- session 历史达到预算阈值时，使用“较早消息摘要 + 最近 4 条消息原文”。
- 当前 Run 仍由 `ContextWindowGuard` 使用“`history_summary` + 最近 2 个步骤输出”策略。
- session 历史与当前 Run 摘要分别使用 `sessionContextSummary`、`runContextSummary`，不会互相覆盖。

## 健康治理收口

- 删除与 `sessionId` 重复的 `memoryConversationId`，统一使用 `conversationScope` 表达持久化会话边界。
- 删除 Flow Runtime 已不再使用的 ChatMemory advisor 请求期参数透传。
- 删除恒定为 `COMPLETED` 的 `message_status` 字段。
- 删除可被 `(run_id, role)` 唯一索引覆盖的单列 `run_id` 索引。
- `context_units` 用于后续历史压缩阈值判断，不再只写入数据库但不消费。
- MCP SDK 的请求超时和初始化超时分别配置；慢启动 stdio server 不再落回 SDK 默认初始化窗口。
- `scripts/dev/run-local-smoke.ps1` 将 RAG evidence 与 `context_guard` 解耦：RAG evidence 作为稳定断言，依赖模型输出长度的 `context_guard` 作为可观察项。
- `scripts/dev/run-local-smoke.ps1` 的可选 `context_guard` 检查兼容不含 `subType` 的事件，避免严格属性访问导致已完成 smoke 被误判为失败。

## 真实 smoke

- Docker `pgvector`、Elasticsearch 和 5 个 MCP Client 均完成初始化。
- Flow、工具路由和 RAG evidence 链路已通过本地真实模型请求。
- 同一 `sessionId` 连续两轮请求已验证：第二轮流式 `context_boundary` 与 `GET /api/v1/agent/run/{runId}` 均能返回执行前注入的 session 历史快照。
- 使用标准本地启动入口启用 PGVector 后，短 RAG 请求已验证 `vectorHits=6`、`bm25Hits=6`、`finalEvidence=6`，不是只依赖 Elasticsearch 的降级链路。

## 答辩表达

> 记忆模块不是无限堆历史，也不是长期用户画像。系统只持久化同一 session 的用户输入和最终回答，并按预算保留较早消息摘要与最近原文；当前 Run 的步骤输出另行压缩。这样服务重启后可以恢复短期对话，同时避免内部 Agent prompt 污染用户记忆。
