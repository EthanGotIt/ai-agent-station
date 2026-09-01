# Commerce Guardian Agent 运行手册

## 配置

本地与 CI 工具链保持一致：Python 3.14、Node.js 24、JDK 17；订单服务夹具由 Python 3.14 进程运行。项目只维护 CI 与本地验收，不提供 CD 部署资产。

敏感配置只通过环境变量注入：`MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` 和 `DEEPSEEK_API_KEY`。DeepSeek 请求固定使用 `deepseek-v4-pro` thinking 模式；`DEEPSEEK_BASE_URL`、输出上限、重试次数、模型 HTTP 超时、Thread 上下文预算、队列容量、各层超时、SSE 心跳（`AI_AGENT_SSE_HEARTBEAT_INTERVAL`）和 Worker 轮询参数均在 `application.yml` 中以环境变量覆盖。受控闭环默认开启（`AI_AGENT_CONTINUATION_ENABLED=true`），最多自动续跑 3 轮（`AI_AGENT_MAX_CYCLES=3`）；Windows/JDK 17 本地验收默认使用 Reactor Netty 与 Tomcat NIO2，协议可用 `AI_AGENT_TOMCAT_PROTOCOL` 覆盖。当前 Codex Windows 沙箱仍可能在实际 DeepSeek 请求时阻断 Netty selector loopback；出现“Agent 执行失败”时先在普通 Windows 终端复核网络/JDK，再判断模型或业务问题。需要隔离验证时可将这些变量显式注入启动进程。Spring Boot 不会自动读取被 Git 忽略的 `.env` 文件；使用该文件时必须先把它加载到当前启动进程，旧的 `AI_AGENT_MODEL_*` 变量不会被当前应用读取。

订单适配器默认使用本地 `local` 实现；验收外部订单服务时设置 `AI_AGENT_ORDER_GATEWAY=http`、`AI_AGENT_ORDER_BASE_URL` 和可选的 `AI_AGENT_ORDER_HTTP_TIMEOUT`。HTTP 订单服务必须按 `/orders/search`、`/orders/{id}`、`/orders/{id}/refund`、`/orders/{id}/expedite` 和 `DELETE /orders/{id}` 契约提供 JSON 响应；应用会发送 `X-User-Id`，所有写操作还会发送 `Idempotency-Key`。订单隐藏/恢复接口已移除，历史 `HIDDEN_AT` 仅为旧数据读取兼容，不得再写入。仓库没有约定额外的外部鉴权环境变量，启用真实服务前需取得其服务端鉴权和响应契约；不要把凭据写入文档或提交。

Thread 的 `PATCH /api/agent/threads/{threadId}` 只允许更新标题；历史 `ARCHIVED` Thread 可按状态读取，但不再提供归档或恢复写操作。

## 初始化与启动

1. 使用可丢弃的本地 MySQL 执行 `docs/dev-ops/mysql/commerce-guardian-agent.sql`。脚本会删除旧表，禁止用于生产数据。
2. 已有数据库只能通过 Flyway 增量升级。执行前先对确认过的数据库做备份，并在专用克隆库验证迁移；不得用基线 SQL 重建或覆盖已有业务数据。
3. 在 `commerce-guardian-agent-app/.env` 填写真实 `DEEPSEEK_API_KEY`，并在启动前加载环境变量。PowerShell 可使用以下不打印值的方式：

   ```powershell
   $configPath = 'commerce-guardian-agent-app/.env'
   Get-Content -LiteralPath $configPath | ForEach-Object {
       if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
           [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
       }
   }
   mvn spring-boot:run -pl commerce-guardian-agent-app
   ```

4. 运行前端前，在仓库根目录执行 `node scripts/npm_ci_fallback.mjs`；脚本默认按 npmmirror → npmjs 顺序尝试，也可用 `NPM_REGISTRIES` 覆盖顺序，然后执行 `cd agent-fronted; npm run dev`。

本地演示身份通过 `X-User-Id: demo-user-1` 传递；真实部署应在网关完成认证并由应用认证适配器提供用户 ID。

## 验收路径

```powershell
$headers = @{ "X-User-Id" = "demo-user-1" }
$thread = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8090/api/agent/threads -Headers $headers -ContentType application/json -Body '{"title":"演示 Thread"}'
$threadId = $thread.threadId
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8090/api/agent/threads/$threadId/turns" -Headers $headers -ContentType application/json -Body '{"clientRequestId":"demo-1","message":"查询订单 ORDER-PAID-001 的状态"}'
Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:8090/api/agent/threads/$threadId/items?afterSequence=0&limit=200" -Headers $headers
# 已返回的 turnId 可用于只读轨迹回放：GET /api/agent/turns/{turnId}/execution
```

退款或催发货请求在固定 Workflow 的 `AUTHORIZE` 节点生成独立 `WORKFLOW_CHECKPOINT`；批准后命令进入 Worker，缺少订单号或退款原因时才生成 `QUESTION_CARD`。外部动作成功或核验/重试需要 Agent 继续判断时，会追加最多 3 轮的 `AGENT_CONTINUATION` Turn；可通过 Items 和 SSE 观察 `TOOL_*`、`WORKFLOW_*`、`WORKFLOW_STEP`、`AGENT_DECISION`、`EXTERNAL_ACTION_STATUS` 和 Turn 终态。续跑与 Workflow 结果仍以持久化 Items 为准，SSE 只负责实时体验和断线恢复。

## 排错

- `409 THREAD_AWAITING_ANSWER`：当前 Thread 有开放 QuestionCard，必须先回答、拒绝或取消。
- `429 THREAD_QUEUE_FULL` / `AGENT_QUEUE_FULL`：等待现有 Turn 完成或取消排队请求。
- SSE 断线：先请求 Items API，使用返回的 `nextAfterSequence` 重新订阅 events；最终状态以持久化 Item 为准。
- `MANUAL_RETRY_REQUIRED`：确认外部系统没有成功写入后调用 `/api/agent/workflow-runs/{runId}/retry`，接口保持原幂等键。
- `AGENT_DECISION_MISSING`：模型两次未形成受控终止决策；页面只显示“再次尝试”，该操作通过原请求内容创建新的 Turn 和 `clientRequestId`，不会自动重放旧 Turn 或复用旧请求 ID。
- `RUNTIME_RESTARTED`：重启时 ACTIVE Turn 会失败收敛，排队 Turn、QuestionCard 和外部命令继续恢复。

## 验证命令

```text
python -m scripts.convention_check
python -m unittest discover -s scripts/tests -p "test_*.py"
python -m scripts.runtime_eval
mvn dependency:analyze -DskipTests
mvn clean '-DskipTests=false' test
cd agent-fronted
npm run typecheck
npm test -- --run
npm run test:component
npm run build
```
