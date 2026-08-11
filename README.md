# AI Agent Station

基于 Spring AI 2 与 AgentScope 2.0 的电商履约与售后智能助手。项目使用统一的 Session FIFO 队列协调持久化 Workflow QuestionCard；确定性订单/退款事务走 Workflow，开放式分析与低风险会话偏好写入走 AgentScope ReAct。

## 模块

- `ai-agent-station-core`：统一队列、请求生命周期、路由协议、会话记忆、QuestionCard 编排、Workflow 与输出协议。
- `ai-agent-station-infrastructure`：AgentScope、Qwen Router、Spring AI Session、MyBatis-Plus、Local/HTTP OrderGateway。
- `ai-agent-station-app`：Spring Boot 装配、类型化配置、HTTP/SSE Controller 与异常转换。
- `agent-console`：Vite + React + TypeScript 演示控制台，展示 SSE、QuestionCard、ASK 和取消状态。

依赖方向固定为 `app → core`、`app → infrastructure`、`infrastructure → core`。Workflow 源码位于 `ai-agent-station-core/src/main/java/cn/ethan/core/workflow`，与应用共同编译和部署，不拆为独立 JAR。

## API

```text
POST   /api/v1/agent/chat
POST   /api/v1/agent/chat/stream
POST   /api/v1/agent/workflow-runs/{runId}/answers
POST   /api/v1/agent/workflow-runs/{runId}/answers/stream
GET    /api/v1/agent/memories?sessionId=...
POST   /api/v1/agent/memories
PUT    /api/v1/agent/memories/{entryId}
GET    /api/v1/agent/memories/{entryId}/evidence?sessionId=...
DELETE /api/v1/agent/memories/{entryId}?sessionId=...&expectedVersion=...
DELETE /api/v1/agent/requests/{requestId}
```

聊天请求使用 `requestId、sessionId、message`；QuestionCard 回答使用 `questionId、checkpointId、expectedVersion、answers`。身份由 `X-User-Id` 传入。同一 `userId + sessionId` 严格 FIFO，不同 Session 可以并行。

## 模型、提示词与数据

- Router：Core 规则优先；未覆盖的开放问题才由 Spring AI Chat Completions、`qwen3.7-plus`、512 Token 上限的 Thinking 和 Schema 校验兜底。随包发布的 [`agent-router-policy.md`](ai-agent-station-infrastructure/src/main/resources/prompt/agent-router-policy.md) 只定义受控 executor、operation 与冲突优先级，缺失或空白会阻止启动。
- ReAct：AgentScope 2.0，默认 `qwen3.7-plus` 并开启 Thinking；百炼模型原生工具保持关闭，后续外部能力只通过框架 MCP 组件接入。只有对照验收通过后才允许显式升级为 `qwen3.8-max`。
- ReAct 注册唯一的 classpath AgentSkill [`agent-station-business-orchestration`](ai-agent-station-infrastructure/src/main/resources/agentscope/skills/agent-station-business-orchestration/SKILL.md)，用来指导 Tool 选择和只读无代码编排。它不是 Router Policy，不承载退款资格等业务事实，也不改变 Tool Schema、权限或 Workflow 边界。
- 不使用运行时 ToolGroup 隐藏业务 Tool：六个生产 Tool 始终由服务端注册，Skill 只提供指导；JSON 结构交给 Schema，工具范围交给服务端工具集与权限，Thinking 隔离交给事件边界。
- Thinking 原文只参与模型内部推理，不进入同步响应、SSE 或日志；SSE 仅公开 `thinking_started`、`thinking_completed` 进度。
- `order-inquiry` 支持 `QUERY`、`TRACK`、`DIAGNOSE`：缺少订单号时从近期订单生成选择卡；诊断缺少问题类型时继续生成卡；每次恢复都会实时复查订单归属。
- `after-sales-refund` 支持 `APPLY`、`QUERY_STATUS`：退款原因/说明/最终确认均由持久化 QuestionCard 收集。未发货且金额完整时创建幂等退款命令，已发货或签收七天内进入可查询的人工审核申请；取消、已退款或超期订单被拒绝。
- ReAct 生产目录包含五个只读 `ALLOW` 工具：`list_recent_orders`、`get_order_snapshot`、`get_logistics_trace`、`get_after_sales_status`、`get_after_sales_policy`；`save_session_preference` 是唯一的低风险 `ASK` 写工具，只能保存当前会话的回答偏好。退款等关键写入仍只能通过 Workflow。
- ReAct 使用 `InMemoryAgentStateStore`，确认通过 `intervention → ConfirmResult` 在同一 SSE 回合继续；结束、超时、取消和异常都会清理状态，不提供 ReAct 恢复。Workflow 的跨请求/重启恢复以 MySQL `WORKFLOW_RUN` 为事实来源。
- 会话记忆默认不生成也不使用。每个请求可通过 `memory.generate`、`memory.use` 覆盖；偏好会实际影响 ReAct 的语言、格式和详略，任务上下文只以建议值方式出现在 Workflow QuestionCard，绝不自动写入业务参数。
- 数据库使用大写物理名称：`AI_AGENT_STATION`、`DEMO_ORDER`、`DEMO_ORDER_ITEM`、`DEMO_LOGISTICS_EVENT`、`DEMO_AFTER_SALES_CASE`、`WORKFLOW_RUN`、`WORKFLOW_RUN_EVENT`、`DEMO_REFUND_COMMAND`、`AI_SESSION`、`AI_SESSION_EVENT`、`AGENT_MEMORY_SOURCE`、`AGENT_MEMORY_ENTRY`、`AGENT_MEMORY_EVIDENCE`。

Spring Boot 只使用一个主配置文件：[application.yml](ai-agent-station-app/src/main/resources/application.yml)。环境变量模板见 [ai-agent-station-app/.env.example](ai-agent-station-app/.env.example)。

## 验证

```powershell
python -m scripts.convention_check
python -m unittest discover -s scripts/tests -p "test_*.py"
python -m scripts.plan_audit --strict
mvn clean '-DskipTests=false' test

cd agent-console
npm test
npm run build
```

启动控制台：

```powershell
cd agent-console
npm install
npm run dev
```

Vite 会将 `/api` 代理到本地 8090 端口；控制台不会在 `localStorage` 保存 Prompt、工具参数或记忆正文。

当前交付只覆盖本地开发与自动验证，不要求上线或现场演示，也不依赖 Docker、Docker Compose、Nginx 或 TLS 证书。

真实 DashScope 验收需要用户另行授权，并使用独立非生产环境。获准后可额外执行 `python -m scripts.live_acceptance --skill-stability-runs 5`，只重复 Router/Skill 场景并输出脱敏的 5/5 成功率报告；默认验证不会调用该命令。

详细说明见 [文档索引](docs/README.md)、[架构文档](docs/architecture.md)、[运行手册](docs/runbook.md)、[执行验收矩阵](docs/execution-plan.md) 和 [任务交接](docs/task-handoff.md)。
