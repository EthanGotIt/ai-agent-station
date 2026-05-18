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

- `OPENAI_API_KEY`：对话模型
- `JINA_API_KEY`：向量模型（默认 `jina-embeddings-v5-text-small`，`1024` 维）

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
  - 计划中工具步骤只能使用白名单内工具
  - 最终结果包含结论和建议

## 场景三：RAG 证据输出

- 前置动作：
  - 先执行一次 Markdown Parent-Child 导入
- 输入：
  - `请仅基于已导入的 Markdown 知识回答 Spring AI MCP Client 常见的接入方式，不要调用外部 MCP 搜索工具。`
- 预期关键事件：
  - `context_guard`
  - `rag_evidence`
  - `summary`
  - `complete`
- 预期结果摘要：
  - 命中 child chunk 后回查 parent
  - `rag_evidence` 中可看到来源、章节、召回 query、融合分数
  - 最终回答基于章节级上下文输出，而不是零散片段
  - 当上下文达到阈值时，会额外出现 `context_guard`
