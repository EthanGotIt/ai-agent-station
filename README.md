# AI Agent Station

AI Agent Station 是面向企业 Java 项目知识与技术资料调研的轻量受控 Agent Runtime。系统在内部项目知识、版本化官方文档和外部技术资料之间选择证据来源，通过受控 Action Loop、Evidence Board 和引用校验生成可追踪回答。

## 技术基线

- Spring Boot `4.1.0`、Spring AI `2.0.0`、JDK `17`
- MyBatis、MySQL、PostgreSQL + PGVector、可选 Elasticsearch
- Spring AI MCP Client，支持 Stdio 和 Streamable HTTP
- Chat：`qwen3.7-max`
- Embedding：`text-embedding-v4`，`1024` 维

Spring AI 2 迁移细节见 [docs/spring-ai-2-upgrade.md](docs/spring-ai-2-upgrade.md)。

## 核心设计

### Evidence-Governed Harness

主链路只有三个高层动作：

- `RETRIEVE`：选择 `PROJECT_KNOWLEDGE / OFFICIAL_DOCS / WEB_RESEARCH` 之一获取证据，不直接生成最终答案。
- `ASK_CLARIFY`：缺少必要约束时追问并终止当前 Run。
- `FINALIZE`：后端根据 Evidence Board 统一生成最终回答，Action JSON 不能携带答案。

模型负责选择高层动作，确定性 Policy 负责限制最大四轮决策、最多两次 evidence retrieval、最多一次外部检索、重复来源/query 和取消终止。Action 解析失败时不会无条件直接回答，无证据时按问题类型降级到本地检索或受控拒答。

### Adaptive Agentic Retrieval

生产检索只有一个入口：`EvidenceRetrievalService`。

```text
用户问题
-> Harness 决定高层 evidence source
-> EvidenceRetrievalService
   -> PROJECT_KNOWLEDGE: PGVector 默认，精确术语或语义无结果时启用 BM25
   -> OFFICIAL_DOCS: 按需路由 Context7
   -> WEB_RESEARCH: 按需路由 Exa
-> Evidence Board 去重、记录来源与检索轮次
-> 下一轮 Harness 评估 evidence 或改写 query
-> GroundedAnswerService 生成 [E1] 引用并校验
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

MCP 不在 Run 开始时全量路由。只有 `OFFICIAL_DOCS` 或 `WEB_RESEARCH` 动作发生时，系统才按来源选择对应 MCP，并再次执行只读过滤。

- 允许：`search / docs / fetch / read / get / open / list / resolve`
- 禁止：`create / update / write / send / notify / memory / shell`

治理边界为“按来源路由、注入前 allowed set、调用期 GuardedToolCallback”。

### Session 短期记忆

- `ai_agent_conversation_message` 只保存用户输入和最终回答原文。
- 只有同时存在 USER 和 ASSISTANT 的成功完整 Turn 会进入后续 Prompt，失败或取消 Run 的孤立 USER 不会被注入。
- `ai_agent_conversation_session` 保存结构化滚动摘要、消息游标、乐观锁版本和 30 天过期时间。
- 上下文使用“结构化摘要 + 最近四个完整 Turn”，预算不足时淘汰整个旧 Turn，不截断单条消息。
- 摘要只保存目标、约束、用户确认的决策、未解决问题和回答偏好，不保存工具输出、外部事实或模型猜测。
- 清除接口 `DELETE /api/v1/agent/session/{sessionId}/memory` 只在 `dev` Profile 注册。

这仍是 Session 短期记忆，不是长期用户画像或向量记忆系统。

### 上下文预算

上下文按单次模型调用组装，不累计多次模型输入输出。`PromptBudgetAssembler` 的保留优先级为：

1. 当前问题
2. 项目规则
3. Evidence Board
4. Session 上下文
5. Harness observation

配置项为 `ai-agent.context.max-context-units`。估算器是中英文 heuristic，不是精确 tokenizer。

## 运行态与事件

执行记录保存在：

- `ai_agent_run`
- `ai_agent_step_run`

主要 NDJSON 事件：

- `context_boundary`
- `harness_observation`
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

当前回归基线为 187 个默认测试和 108 个 integration 测试，均为 0 failure/0 error。live 已验证 Harness、本地 evidence 和 MCP ToolCallback 注入，完整外部 evidence 及三组评测仍受百炼账户额度限制，详见升级总控文档。

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
