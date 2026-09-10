# Commerce Guardian Agent 运行手册

## 配置

本地与 CI 工具链保持一致：Python 3.14、Node.js 24、JDK 17；订单服务夹具由 Python 3.14 进程运行。项目只维护 CI 与本地验收，不提供 CD 部署资产。

敏感配置只通过环境变量注入：`MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` 和 `DEEPSEEK_API_KEY`。DeepSeek 请求固定使用 `deepseek-v4-pro` thinking 模式；`DEEPSEEK_BASE_URL`、模型单次输出上限（默认 1,024）、Turn 累计生成额度（`AI_AGENT_MAX_OUTPUT_TOKENS_PER_TURN`，默认 8,192）、重复工具失败阈值（`AI_AGENT_REPEATED_TOOL_FAILURE_THRESHOLD`，默认 3）、模型 HTTP 超时、Thread 上下文预算（默认 65,536，输出预留 1,500）、队列容量、各层超时、SSE 心跳（`AI_AGENT_SSE_HEARTBEAT_INTERVAL`）和 Worker 轮询参数均在 `application.yml` 中以环境变量覆盖。2A-1 生产路径先固定已提交最大 Sequence，再按每页 300 条读取完整原始 Items；2A-2 在完整 Prompt 达到 80% 压力时先裁剪超过 8,000 字符的 Tool Result（保留头 4,096、尾 1,024），仍有压力才摘要旧的完整 Turn/Tool 批次，默认保留最近 16%。压缩使用 V2 派生快照，CAS 冲突读取胜者；原始 Items、SSE 序号和旧 V1 摘要均不改写。`AI_AGENT_THREAD_COMPACTION_ENABLED`、`AI_AGENT_THREAD_COMPACTION_TRIGGER_RATIO`、`AI_AGENT_THREAD_COMPACTION_RETAIN_RATIO`、`AI_AGENT_THREAD_SUMMARY_MAX_OUTPUT_TOKENS`、`AI_AGENT_THREAD_TOOL_PRUNE_*` 和 `AI_AGENT_THREAD_MAX_OVERFLOW_RETRIES` 可调整上述策略；旧 `AI_AGENT_THREAD_SNAPSHOT_TRIGGER_ESTIMATED_TOKENS` 仍兼容接收但不参与新算法。摘要调用与 Turn 共用截止时间及 8,192 token 输出额度；供应商明确上下文溢出时最多按配置重试一次，必须先有严格缩减并改变视图，否则以 `CONTEXT_BUDGET_EXCEEDED` 停止；请求前压力处理在完整 Prompt 未超过硬预算时允许继续发送。受控闭环默认开启（`AI_AGENT_CONTINUATION_ENABLED=true`），最多自动续跑 3 轮（`AI_AGENT_MAX_CYCLES=3`）；Windows/JDK 17 本地验收默认使用 Reactor Netty 与 Tomcat NIO2，协议可用 `AI_AGENT_TOMCAT_PROTOCOL` 覆盖。当前 Codex Windows 沙箱仍可能在实际 DeepSeek 请求时阻断 Netty selector loopback；出现“Agent 执行失败”时先在普通 Windows 终端复核网络/JDK，再判断模型或业务问题。需要隔离验证时可将这些变量显式注入启动进程。Spring Boot 不会自动读取被 Git 忽略的 `.env` 文件；使用该文件时必须先把它加载到当前启动进程，旧的 `AI_AGENT_MODEL_*` 变量不会被当前应用读取。

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

前端订单卡片的动作回执按业务事实区分：确认卡打开时为“需要确认”，命令为 PENDING/PROCESSING/RETRY_WAIT 时分别显示等待执行、提交中或等待自动重试，SUCCEEDED 显示成功；若成功回执的核验状态为 PENDING，则显示“已受理、最新状态暂未核验”并只发起 REFRESH_ORDER 查询；重试耗尽显示“需要人工重试”。后续 Agent 续接失败或预算/历史停止只作为非阻断提示，不覆盖已成功的外部动作。

### 2A-1 MySQL 集成验收

`AgentItemStoreMySqlIT` 默认不会连接数据库；启用 `context-acceptance` profile 后，它从 `MYSQL_URL`、`MYSQL_USERNAME` 和 `MYSQL_PASSWORD` 读取连接信息，在 MySQL 8.4 上创建带随机后缀的临时库，复用 `docs/dev-ops/mysql/commerce-guardian-agent.sql` 建表，并在结束时删除该临时库。测试只使用本次创建的库，不执行固定业务库重建。

```powershell
$configPath = 'commerce-guardian-agent-app/.env'
Get-Content -LiteralPath $configPath | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
mvn -B -Pcontext-acceptance '-DskipTests=false' verify
```

CI 的 Maven Job 使用 MySQL 8.4 服务运行同一 profile。未启用 profile 的普通 `mvn verify` 会编译该 `*IT`，但不会连接数据库。

### 2A-2 压缩与恢复复核

2A-2 使用 `V10__persist_context_compaction_metadata.sql` 为已有库增加 V2 快照元数据；先在一次性克隆库执行迁移并核对 `FORMAT_VERSION`、来源 Sequence 和摘要版本列，再在应用重启后验证原始 Item 数量与 Sequence 不变。V1 快照会被忽略并从原始历史重建，快照提交按所属 Thread 锁和最新快照标识 CAS；并发压缩只有一个摘要胜者，失败方不重复调用摘要模型。2B-1 继续执行 `V11__persist_workflow_orchestration_version.sql`，为历史 Run 写入 `LEGACY_V1`，明确订单号催发货的新 Run 才能在开关打开时写入 `EXPEDITE_GRAPH_V1`。新增 `AgentWorkflowMySqlIT` 使用模块 `.env` 注入连接信息，在随机临时库从 V9 基线迁移到 V11，并通过 Spring 事务代理、`SqlSessionTemplate` 与生产 MyBatis Store 验证确认事务回滚、锁读、CAS 竞争、来源版本读取和命令幂等；Flyway 检查通过 API 和 `INFORMATION_SCHEMA` 查询，避免依赖 Windows 表名大小写。2B-1 的催发货图状态、2B-2 的生产技术快照恢复都已实现，生产副本故障注入和跨进程恢复属于单独现场门禁。

### 2B-1 / 2B-2 Workflow 复核

2B-1 试点默认关闭。仅在一次性验收环境中显式打开明确订单号的催发货图：

```powershell
$env:AI_AGENT_EXPEDITE_GRAPH_ENABLED = 'true'
mvn spring-boot:run -pl commerce-guardian-agent-app
```

验证新 Run 的 Items 依次出现订单读取、资格核验、AUTHORIZE 确认和 Worker 交接；批准前不得出现外部命令，批准后保持 WAITING_EXTERNAL_ACTION，Worker 结算后才出现 EXTERNAL_ACTION_STATUS。重启发生在确认前、命令等待中或重试等待中时，复核同一 Run/Command 被恢复，不创建第二个命令或第二次业务写入。非明确订单号、其他动作和已有 Run 必须继续使用 LEGACY_V1。

2B-2 的 MybatisLangGraphCheckpointSaver 将技术节点和版本元数据写入 AGENT_GRAPH_SNAPSHOT；故障注入时删除、损坏或篡改技术快照，应用应根据 WorkflowRun、QuestionCard、Checkpoint 和订单事实重建，并拒绝未知编排版本。生产库复核必须使用已备份的一次性副本；当前开关仍默认关闭，未完成故障注入和跨进程现场证据前不得宣称试点已默认启用。

使用真实 `ChatClient`/`ChatModel`、唯一 `ToolCallingAdvisor` 和假流式模型复核以下顺序：完整 Prompt 达到 80% 后先裁剪 Tool Result，裁剪已解除压力时不调用摘要；仍有压力时只摘要完整 Turn/Tool 批次，保留最近 16% 和当前请求。摘要失败、空响应、断流、取消、额度不足、CAS 冲突或持久化失败均保留最后有效视图。模拟供应商 `context_length_exceeded` 只允许在严格缩减后重试一次，普通 400、网络错误和摘要模型错误不得进入溢出重试。日志和 `EXECUTION_EVENT` 只允许出现范围、计数、估算和版本，不得输出 Prompt、Thinking、摘要正文或敏感事实。

## 排错

- `409 THREAD_AWAITING_ANSWER`：当前 Thread 有开放 QuestionCard，必须先回答、拒绝或取消。
- `429 THREAD_QUEUE_FULL` / `AGENT_QUEUE_FULL`：等待现有 Turn 完成或取消排队请求。
- SSE 断线：先请求 Items API，使用返回的 `nextAfterSequence` 重新订阅 events；最终状态以持久化 Item 为准。
- `MANUAL_RETRY_REQUIRED`：确认外部系统没有成功写入后调用 `/api/agent/workflow-runs/{runId}/retry`，接口保持原幂等键。
- `AGENT_DECISION_MISSING`：模型两次未形成受控终止决策；页面只显示“再次尝试”，该操作通过原请求内容创建新的 Turn 和 `clientRequestId`，不会自动重放旧 Turn 或复用旧请求 ID。
- `CONTEXT_BUDGET_EXCEEDED` / `OUTPUT_BUDGET_EXCEEDED`：本轮资源预算已耗尽；页面显示具体停止原因，已持久化的 Workflow、QuestionCard 或外部动作事实保持不变，不把 Turn 终态当作业务成功。
- `CONTEXT_HISTORY_INVALID`：固定水位内的 Item 页面重复、乱序、越界或提前结束；本轮不会把不完整历史发送给模型，先检查 Item 游标和持久化读取适配器。
- `TOOL_REPEATED_FAILURE`：相同工具、规范化参数和稳定错误码连续失败达到阈值；Runtime 以 `FALLBACK` 失败收口，确认订单事实或调整请求后再试。
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
