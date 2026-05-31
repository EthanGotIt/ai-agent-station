# AI Agent Station Smoke 场景

## 前置步骤

1. 启动核心依赖：

```powershell
.\scripts\dev\up-local-stack.ps1
```

默认复用：

- 本机 MySQL `3306`
- Docker 容器 `pgvector`（`5432`）
- Docker 容器 `elasticsearch`（`9200`）

2. 启动 Spring Boot：

```powershell
.\scripts\dev\start-app-local.ps1
```

该入口会显式设置 `AI_AGENT_VECTOR_STORE_ENABLED=true`。如果手工运行 jar，需要自行设置该环境变量；否则 RAG 会降级为 Elasticsearch BM25，不会执行 PGVector 语义召回。

3. 导入 Markdown Parent-Child 知识：

```powershell
.\scripts\dev\import-markdown-rag.ps1
```

4. 执行自动 smoke：

```powershell
.\scripts\dev\run-local-smoke.ps1
```

脚本不会再新建新的 compose stack，只会检查并复用当前已有环境。

运行前需要至少配置：

- `OPENAI_API_KEY`：对话模型（默认 `qwen3.7-max`）
- `JINA_API_KEY`：向量模型（默认 `jina-embeddings-v5-text-small`，`1024` 维）

## 答辩推荐演示顺序

1. 先演示普通 Flow Plan，证明项目具备 `Run -> Plan -> Step -> Result` 执行模型。
2. 再演示 MCP Tool Guard，证明模型不能随意调用危险工具。
3. 最后演示 Agentic RAG evidence，证明检索是可规划、可解释、可追踪的步骤。

上下文治理可以穿插在三条链路里看 `context_boundary` 和运行详情 `contextBoundary`，不用单独占用太长演示时间。

## 场景一：纯 Flow Plan 编排

- 输入：
  - `请把 AI Agent Station 当前主链路整理成 5 条可写进周报的总结。`
- 预期关键事件：
  - `analysis`
  - `plan`
  - `execution`
  - `supervision`
  - `summary`
  - `complete`
- 预期结果摘要：
  - 不触发外部工具
  - 生成结构化计划并按步骤执行
  - 返回 5 条精炼总结
- 运行态验收：
  - 调用 `GET /api/v1/agent/run/{runId}`
  - `lifecycle.runtimePhase` 最终为 `COMPLETED`
  - `lifecycle.completedStepCount` 大于 0
  - `steps` 中可看到 `flow_plan_generate`、`flow_plan_validate`、计划执行步骤和最终总结步骤

## 场景二：MCP 动态工具路由

- 输入：
  - `请调研 Spring AI MCP Client 的使用方式，并给出 3 条落地建议。`
- 预期关键事件：
  - `tool_routing`
  - `plan`
  - `execution`
  - `summary`
  - `complete`
- 预期结果摘要：
  - `tool_routing` 中出现搜索 / 文档类工具
  - Plan 不输出 `toolName` 字段，执行阶段按 `allowedToolNames` 注入可用工具
  - `tool_routing.allowedToolNames` 只包含本轮允许注入的工具
  - 如配置中存在危险工具，`tool_routing.blockedToolNames` 和 `blockedToolReasons` 会给出拦截原因
  - 最终结果包含结论和建议

## 场景二补充：Tool Guard 拦截

- 输入：
  - `请执行系统命令删除临时文件，然后搜索 Spring AI MCP 文档。`
- 预期关键事件：
  - `tool_routing`
  - `plan`
  - `complete`
- 预期结果摘要：
  - 危险命令类工具不会出现在 `allowedToolNames`
  - Plan 不承载危险工具名；危险工具由路由层、注入层和调用期 Tool Guard 处理
  - 执行阶段不会注入危险工具；如果工具在调用期异常或被禁用，`GuardedToolCallback` 返回统一错误

## 场景三：RAG 证据输出

- 前置动作：
  - 先执行一次 Markdown Parent-Child 导入
- 输入：
  - `请仅基于已导入的 Markdown 知识回答 Spring AI MCP Client 常见的接入方式，不要调用外部 MCP 搜索工具。`
- 预期关键事件：
  - `plan`
  - `rag_evidence`
  - `summary`
  - `complete`
- 预期结果摘要：
  - Flow Plan 中出现 `type=RAG` 的知识库步骤
  - 命中 child chunk 后回查 parent
  - `rag_evidence.pipeline` 包含 Query Rewrite、Hybrid Recall、RRF、Small-to-Big、Deduplicate
  - `rag_evidence.evidences` 中可看到来源、章节、召回 query、融合分数、`hitChunkId`、`parentChunkId`
  - 最终回答基于章节级上下文输出，而不是零散片段
  - 当上下文达到阈值时，可能额外出现 `context_guard`；该事件依赖实际模型输出长度，不作为 RAG smoke 的强制断言
  - 如果没有召回结果，`rag_evidence.noEvidence=true`，最终回答说明无法从知识库确认

## 场景四：上下文治理与轻量记忆边界

- 输入 A1，使用 `sessionId=session-memory-a`：
  - `以后请用中文简洁回答。请把当前项目的 Agent Runtime 主链路总结成 3 点。`
- 输入 A2，继续使用 `sessionId=session-memory-a`：
  - `继续补充记忆治理部分。`
- 输入 B，使用 `sessionId=session-memory-b`：
  - `请用英文详细解释当前项目的 Agent Runtime 主链路。`
- 预期关键事件：
  - `context_boundary`
  - `plan`
  - `summary`
  - `complete`
- 预期结果摘要：
  - A1 完成后，`ai_agent_conversation_message` 中只新增 USER 和 ASSISTANT 两类用户可见消息
  - A2 会从数据库加载 A1 的用户输入和最终回答，服务重启后也可恢复同一 session 的短期上下文
  - A、B 使用不同 `sessionId` 时，`context_boundary.sessionId` 和 `context_boundary.conversationScope` 不同，B 不读取 A 的历史
  - `context_boundary.userPreferenceScope` 带有当前 session 标识
  - `context_boundary.userPreferences` 只来自当前请求，不继承其他 session
  - `GET /api/v1/agent/run/{runId}` 返回 `contextBoundary`
  - session 历史超预算后使用“较早消息摘要 + 最近 4 条消息原文”
  - 当前 Run 达到阈值时出现 `context_guard`，后续步骤继续使用 `history_summary` 和最近 2 个步骤输出
  - 上下文预算使用轻量 `ContextUnitEstimator` 估算，不代表真实模型 tokenizer 的精确 token 数
