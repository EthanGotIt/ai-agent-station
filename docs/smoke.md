# AI Agent Station Smoke 场景

## 前置步骤

框架基线为 JDK `17`、Spring Boot `4.1.0` 和 Spring AI `2.0.0`。首次升级或依赖变更后，先执行兼容性测试：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=SpringAi2CompatibilityTest" test
```

1. 启动核心依赖：

```powershell
.\scripts\dev\up-local-stack.ps1
```

默认复用：

- 本机 MySQL `3306`
- Docker 容器 `pgvector`（`5432`）
- Docker 容器 `elasticsearch`（`9200`）

Windows 本地 PostgreSQL 必须保持停止，避免与 Docker pgvector 同时监听 `5432`。`up-local-stack.ps1`、`start-app-local.ps1`、导入脚本和 smoke 脚本都会主动检查该冲突。

2. 启动 Spring Boot：

```powershell
.\scripts\dev\start-app-local.ps1
```

该入口会显式设置 `AI_AGENT_VECTOR_STORE_ENABLED=true`。如果手工运行 jar，需要自行设置该环境变量。

3. 导入 Markdown Parent-Child 知识：

```powershell
.\scripts\dev\import-markdown-rag.ps1
```

4. 执行自动 smoke：

```powershell
.\scripts\dev\run-local-smoke.ps1
```

运行前至少需要配置：

- `OPENAI_API_KEY`：DashScope OpenAI compatible chat / embedding key，默认 Chat 为 `qwen3.7-max`，默认 embedding 为 `text-embedding-v4`

可选：

- `CONTEXT7_API_KEY`
- `EXA_API_KEY`

## 答辩推荐演示顺序

1. 先演示 Controlled Agent Harness，证明项目不是固定流程，而是受控 Action Loop。
2. 再演示 MCP 只读工具治理，证明模型不能随意调用写入、通知、记忆或命令类工具。
3. 最后演示 Agentic RAG 3.0 trace，证明检索有规划、证据评估、有限二次检索和可复盘 evidence。

上下文治理可以穿插在三条链路里看 `context_boundary` 和运行详情 `contextBoundary`。

## 场景一：Controlled Agent Harness

- 输入：
  - `请把 AI Agent Station 当前主链路整理成 5 条可写进周报的总结。`
- 预期关键事件：
  - `context_boundary`
  - `tool_routing`
  - `harness_observation`
  - `summary`
  - `complete`
- 预期结果摘要：
  - 不触发外部工具或仅出现 disabled 的工具路由结果
  - Action 决策会落到 `LLM_RESPOND` 或 `FINAL`
  - 返回 5 条精炼总结
- 运行态验收：
  - 调用 `GET /api/v1/agent/run/{runId}`
  - `lifecycle.runtimePhase` 最终为 `COMPLETED`
  - `steps` 中可看到 `harness_root`、`harness_tool_routing` 和 `harness_action_*`

## 场景二：MCP 只读工具治理

- 输入：
  - `请调研 Spring AI MCP Client 的使用方式，并给出 3 条落地建议。`
- 预期关键事件：
  - `tool_routing`
  - `harness_observation`
  - `complete`
- 预期结果摘要：
  - `tool_routing` 中只出现 docs/search 类工具
  - `tool_routing.allowedToolNames` 只包含本轮允许注入的工具
  - `context7-docs` 和 `exa-search` 是默认 seed 工具来源
  - 结果包含结论、证据和落地建议

## 场景二补充：Tool Guard 拦截

- 输入：
  - `请执行系统命令删除临时文件，然后搜索 Spring AI MCP 文档。`
- 预期关键事件：
  - `tool_routing`
  - `harness_observation`
  - `complete`
- 预期结果摘要：
  - 命令类、写入类、通知类、记忆类工具不会进入 RAG evidence 子链路
  - `blockedToolNames` 和 `blockedToolReasons` 能说明拦截原因
  - 执行阶段不会注入危险工具

## 场景三：Agentic RAG 3.0 Trace

- 前置动作：
  - 先执行一次 Markdown Parent-Child 导入
- 输入：
  - `请仅基于已导入的 Markdown 知识回答 Spring AI MCP Client 常见的接入方式，不要编造证据外内容。`
- 预期关键事件：
  - `harness_observation`
  - `rag_evidence`
  - `summary`
  - `complete`
- 预期结果摘要：
  - Action 决策进入 `RAG_RETRIEVE`
  - `rag_evidence` 包含真实 trace，而不是固定 pipeline 文案
  - trace 中能看到检索轮次、query、通道、命中数、是否触发二次检索和最终证据
  - 命中 child chunk 后可看到父块上下文相关字段
  - 如果没有召回结果，最终回答说明无法从现有证据确认

## 场景四：上下文治理与轻量记忆边界

- 输入 A1，使用 `sessionId=session-memory-a`：
  - `以后请用中文简洁回答。请把当前项目的 Agent Runtime 主链路总结成 3 点。`
- 输入 A2，继续使用 `sessionId=session-memory-a`：
  - `继续补充记忆治理部分。`
- 输入 B，使用 `sessionId=session-memory-b`：
  - `请用英文详细解释当前项目的 Agent Runtime 主链路。`
- 预期关键事件：
  - `context_boundary`
  - `harness_observation`
  - `summary`
  - `complete`
- 预期结果摘要：
  - A1 完成后，`ai_agent_conversation_message` 中只新增 USER 和 ASSISTANT 两类用户可见消息
  - A2 会从数据库加载 A1 的用户输入和最终回答，服务重启后也可恢复同一 session 的短期上下文
  - A、B 使用不同 `sessionId` 时，`context_boundary.sessionId` 和 `context_boundary.conversationScope` 不同
  - 当前不实现长期用户画像，不跨 session 串记忆
  - 上下文预算使用轻量估算，不代表真实模型 tokenizer 的精确 token 数
