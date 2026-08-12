# AI Agent Station 运行手册

## 前置条件与启动

- JDK 17、Maven 3.9+、MySQL 8.x。
- 配置 `DASHSCOPE_API_KEY`；Router 与 ReAct 默认均为 `qwen3.7-plus`。仅在真实对照验收通过后，才可将 ReAct 显式设为 `qwen3.8-max`；不要用模型升级替代规则路由、工具输入或超时问题的修复。
- 新库执行 `docs/dev-ops/mysql/sql/ai-agent-station.sql`；已有库必须先备份，再依次执行 `manual-upgrade-order-diagnosis.sql`、`manual-upgrade-after-sales-refund.sql`、`manual-upgrade-questioncard-memory.sql`、`manual-upgrade-session-memory-v2.sql`、[领域 V2 升级](dev-ops/mysql/sql/manual-upgrade-domain-v2.sql) 和 `manual-upgrade-memory-version.sql`。最后一个脚本为记忆乐观锁增加 `VERSION`；所有脚本都只应在备份后的非生产库执行一次。

```powershell
mvn clean '-DskipTests=false' test
mvn -pl ai-agent-station-app -am package
java -jar ai-agent-station-app/target/ai-agent-station-app.jar
```

默认端口为 `8090`。敏感变量通过环境变量注入，应用不读取或打印 `.env` 中的密钥。

## Chat 与 Workflow QuestionCard

```powershell
$body = @{
  requestId = 'request-001'
  sessionId = 'session-001'
  message = '查询订单'
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8090/api/v1/agent/chat `
  -Headers @{ 'X-User-Id' = 'demo-user-1' } `
  -ContentType 'application/json; charset=utf-8' -Body $body
```

缺少必要信息会返回 `status=WAITING_USER_INPUT`、`question` 和 `workflowRun`。回答必须调用，而不是把答案塞回下一次 `/chat`：

```powershell
$answer = @{
  requestId = 'answer-001'
  sessionId = 'session-001'
  questionId = '{questionId}'
  checkpointId = '{checkpointId}'
  expectedVersion = 0
  answers = @{ orderId = 'ORDER-PAID-001' }
} | ConvertTo-Json -Depth 3

Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8090/api/v1/agent/workflow-runs/{runId}/answers `
  -Headers @{ 'X-User-Id' = 'demo-user-1' } `
  -ContentType 'application/json; charset=utf-8' -Body $answer
```

流式入口为 `/api/v1/agent/chat/stream` 和 `/workflow-runs/{runId}/answers/stream`。二者都先走 `userId + sessionId` FIFO；`SESSION_QUEUE_FULL` 会在 SSE 建连前返回 429。Workflow 只能通过包含版本与检查点的 answers 请求恢复。

订单 Workflow 支持 `QUERY`、`TRACK`、`DIAGNOSE`。物流追踪返回时间线，履约诊断会在问题类型不明确时继续询问。退款 Workflow 依次收集订单、原因、必要说明和最终确认；`PAID` 且金额完整为自动退款，`SHIPPED` 或签收未超过七天为人工审核，人工审核不会创建退款命令。

## ReAct 工具确认

当 ReAct 工具要求确认时，原 SSE 收到 `event: intervention`，其中含 `replyId` 和 `toolCallIds`。保持该 SSE 连接，并从另一个 HTTP 调用提交决定：

```powershell
$decision = @{
  sessionId = 'session-001'
  toolCallIds = @('{toolCallId}')
  decision = 'CONFIRM'
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8090/api/v1/agent/requests/{requestId}/interventions/{replyId} `
  -Headers @{ 'X-User-Id' = 'demo-user-1' } `
  -ContentType 'application/json; charset=utf-8' -Body $decision
```

该决定不进入 FIFO；服务端核对请求、用户、会话和全部工具调用 ID 后继续同一 ReAct 回合。同步 `/chat` 无法承载确认，会返回 `REACT_CONFIRM_REQUIRES_STREAM`。确认超时、取消或回合结束均不保留 ReAct 中断状态。

生产 ReAct 工具包括五个只读查询工具和一个 `save_session_preference` 写工具。只有后者固定返回 `ASK`，且仅能保存 `response.language`、`response.format`、`response.detail` 的规范化值到当前会话；明确且无歧义的自然语言偏好由执行器确定性编排该 Tool，歧义表达不会写入。拒绝在执行器边界直接完成，超时或取消中断等待，三者均不会执行 Tool。`acceptance` Profile 的 `confirmation_probe` 仅用于验证协议，不是生产功能。

## 会话记忆

`AI_AGENT_MEMORY_GENERATION_ENABLED` 与 `AI_AGENT_MEMORY_USAGE_ENABLED` 默认均为 `false`；旧 `AI_AGENT_MEMORY_RECORDING_ENABLED` 仅作为一轮生成开关的兼容别名。请求可携带 `memory.generate`、`memory.use` 覆盖默认值。

成功完成的 ReAct 与 Workflow 由独立 Qwen Flash 后台任务提取为结构化 `PREFERENCE` 或 `TASK_CONTEXT`。任务按会话 30 秒 debounce；队列满、提取失败或进程重启都会跳过本批，不影响用户响应。凭证、支付与认证信息会在提取前后过滤；自动条目最低置信度为 `0.75`，偏好不会过期，任务上下文默认 24 小时过期。

ReAct 最多使用 8 条、4000 字符的受控记忆，并明确作为不可信历史上下文。Router 不读取记忆。Workflow 只将置信度至少 `0.90` 的任务上下文显示为 QuestionCard 建议值；建议值在用户提交 answers 前不会写入 Workflow 参数或绕过实时校验。

管理接口：

- `GET /api/v1/agent/memories?sessionId={sessionId}&limit=50`
- `POST /api/v1/agent/memories`，body 为 `sessionId`、`category`、`memoryKey`、`value`、可选 `expiresAt`
- `PUT /api/v1/agent/memories/{entryId}`，body 与创建字段相同，另需当前 `expectedVersion`
- `DELETE /api/v1/agent/memories/{entryId}?sessionId={sessionId}&expectedVersion={version}`
- `GET /api/v1/agent/memories/{entryId}/evidence?sessionId={sessionId}`

列表默认不返回软删除条目；删除保留 tombstone，自动提取不得复活它。手动创建或编辑会标记为人工维护。编辑或删除时，条目存在但 `expectedVersion` 过期返回 `409 MEMORY_VERSION_CONFLICT`；跨用户或跨会话访问统一返回 404。

## 演示控制台与评测

在后端启动后，另开终端执行：

```powershell
cd agent-console
npm install
npm run dev
```

控制台通过 Vite 代理访问后端，提供订单/物流/售后业务卡片、SSE 时间线、QuestionCard 建议来源、ASK 旁路确认、取消和记忆 CRUD/evidence。SSE 解析支持分块、CRLF、多行 `data` 与尾部缓冲；回答失败不会丢失原 QuestionCard。浏览器只在内存中保留本次界面状态，不将 Prompt、工具参数或记忆正文写入 `localStorage`。

确定性业务回归使用 Java stub 测试；需要连接本地服务时可运行：

```powershell
python -m scripts.evaluation --base-url http://127.0.0.1:8090 --output docs/evaluation/latest-report.md
```

该命令只调用规则可判定的订单场景，生成 Markdown 报告；真实 Qwen/AgentScope 验收仍使用 `scripts.live_acceptance`。

## 取消与错误

`DELETE /api/v1/agent/requests/{requestId}` 需要同一 `X-User-Id`。常见错误：

- `429 SESSION_QUEUE_FULL` / `GLOBAL_QUEUE_FULL`：队列已满。
- `409 REQUEST_ID_CONFLICT`：请求 ID 仍在终态保留期。
- `404 WORKFLOW_RUN_NOT_FOUND`：运行不属于当前用户或会话。
- `409 WORKFLOW_VERSION_CONFLICT`：QuestionCard 已被其他回答推进。
- `409` intervention 响应：确认已过期、归属不匹配或工具列表不完整。

## 验证

```powershell
python -m scripts.convention_check
python -m unittest discover -s scripts/tests -p "test_*.py"
python -m scripts.plan_audit --strict
mvn clean '-DskipTests=false' test
cd agent-console; npm test; npm run build
```

`scripts.live_acceptance` 覆盖订单多阶段 QuestionCard、自动退款、人工审核、状态查询、重启恢复、五个只读工具、真实 `save_session_preference` 的确认/拒绝/超时/取消和 FIFO 取消。百炼模型原生工具（含联网搜索）必须保持关闭；后续外部能力仅通过框架 MCP 组件接入并单独验收。它以 `acceptance` Profile 保留确认探针作为协议诊断，但主要 ASK 场景是生产偏好写入。运行前必须准备独立非生产 MySQL 与 DashScope 凭据；不要用真实凭据执行未经审查的数据库重置。

Router Policy 与 ReAct AgentSkill 的五轮稳定性验收会调用真实模型并重置非生产数据，必须获得明确授权后运行：

```powershell
python -m scripts.live_acceptance --skill-stability-runs 5
```

该模式不重复数据库重置、Workflow 恢复或完整套件，只使用独立 Session 重复 Router/Skill 场景。每个场景必须为 5/5：路由为预期 `REACT`，且 SSE 中的 Tool 生命周期包含预期有序子序列。报告只显示脱敏聚合成功率；若任何一次未命中，命令失败。

V2 已于 2026-08-12 按该方式完成验收；结果见 [V2 验收报告](acceptance/v2-20260812.md)。再次运行仍视为新的真实外部验收，不能因已有结论跳过环境隔离和授权检查。

## 交付范围

当前交付只要求本地开发、自动测试和按需的本地服务验证；不要求上线或现场演示。因此 Docker、Docker Compose、Nginx、TLS 证书和公网访问都不是必备环境。

根目录保留的 Docker 文件仅作将来扩展时的可选工程资产，不属于当前验收，也不应为本地开发下载镜像、创建容器或配置访问密钥。
