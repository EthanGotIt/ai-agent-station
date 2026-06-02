# AI Agent Station Smoke 场景

## 前置步骤

```powershell
.\scripts\dev\up-local-stack.ps1
.\scripts\dev\start-app-local.ps1
.\scripts\dev\import-markdown-rag.ps1
.\scripts\dev\run-local-smoke.ps1
```

至少配置：

- `OPENAI_API_KEY`

默认向量模型为百炼 `text-embedding-v4`，复用 `OPENAI_API_KEY`。

`import-markdown-rag.ps1` 只执行 RAG 入库，默认关闭 Agent 自动装配，不启动无关 MCP 预热。

## 推荐演示顺序

1. 普通 GraphRuntime：观察 `context_boundary -> graph_lifecycle -> todo_update -> summary -> complete`。
2. MCP Tool Guard：观察 `tool_routing.allowedToolNames` 和危险工具拦截。
3. Agentic RAG：观察 `rag_search` 触发后的 `rag_evidence`。
4. Session checkpoint：相同 `sessionId` 连续请求，并检查 PostgreSQL `GraphThread / GraphCheckpoint`。

## 场景一：普通 GraphRuntime

输入：

```text
请把 AI Agent Station 当前主链路整理成 5 条可写进周报的总结。
```

验收：

- 返回 `complete`
- `GET /api/v1/agent/run/{runId}` 的 `lifecycle.runtimePhase=COMPLETED`
- `steps` 中存在 `graph_runtime`
- 复杂任务可观察到 `todo_update`

## 场景二：MCP 动态工具路由

输入：

```text
请调研 Spring AI MCP Client 的使用方式，并给出 3 条落地建议。
```

验收：

- 返回 `tool_routing`
- `allowedToolNames` 只包含本轮筛选后的工具
- MCP ToolCallback 在 GraphRuntime 组装时注入，由模型执行阶段自主决定是否调用
- MCP 客户端在装配阶段只登记配置，后台并发预热；请求命中路由时按需初始化并复用缓存
- MCP 初始化超过请求期等待上限时，本轮回退为无外部工具执行，不阻塞 GraphRuntime
- 调用异常由 `GuardedToolCallback` 归一化

## 场景三：RAG evidence

输入：

```text
请仅基于已导入的 Markdown 知识完成回答。请调用 rag_search 查询 Spring AI MCP Client 常见接入方式，并按结论、证据、落地建议输出。
```

验收：

- 返回 `rag_evidence`
- `pipeline` 包含 Query Rewrite、Hybrid Recall、RRF、Small-to-Big、Deduplicate
- `evidences` 包含来源、章节、召回 query、分数和父块信息

## 场景四：Session checkpoint

连续使用相同 `sessionId`：

```text
以后请用中文简洁回答。请把当前项目的 Agent Runtime 主链路总结成 3 点。
继续补充记忆治理部分。
```

验收：

- 两轮请求均返回 `context_boundary`
- `context_boundary.threadId` 相同
- PostgreSQL `GraphThread` 存在未释放 thread
- PostgreSQL `GraphCheckpoint` 已写入 checkpoint
- MySQL 不再维护 `ai_agent_conversation_message`

## 边界

- `SummarizationHook` 使用近似 token 估算，不等同于模型精确 tokenizer。
- 当前 checkpoint 是 session 短期记忆，不是跨 session 长期用户画像。
- 当前是单 Agent GraphRuntime，不演示多 Agent handoff。
- PGVector 语义召回异常时，`rag_search` 会记录告警并降级到 Elasticsearch BM25；Markdown 重导入仍需要可用的百炼 embedding 服务。
