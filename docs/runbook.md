# v3 运行手册

## 配置

敏感配置只通过环境变量注入：`MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`、`AI_AGENT_MODEL_BASE_URL` 和 `AI_AGENT_MODEL_API_KEY`。模型名称、Thread 上下文预算、队列容量、各层超时和 Worker 轮询参数均在 `application.yml` 中以环境变量覆盖。

## 初始化与启动

1. 使用可丢弃的本地 MySQL 执行 `docs/dev-ops/mysql/sql/ai-agent-station.sql`。脚本会删除旧表，禁止用于生产数据。
2. 设置数据库和模型环境变量。
3. 运行 `mvn spring-boot:run -pl ai-agent-station-app`。
4. 运行前端 `cd agent-console; npm run dev`。

本地演示身份通过 `X-User-Id: demo-user-1` 传递；真实部署应在网关完成认证并由应用认证适配器提供用户 ID。

## 验收路径

```powershell
$headers = @{ "X-User-Id" = "demo-user-1" }
$thread = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8090/api/agent/threads -Headers $headers -ContentType application/json -Body '{"title":"演示 Thread"}'
$threadId = $thread.threadId
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8090/api/agent/threads/$threadId/turns" -Headers $headers -ContentType application/json -Body '{"clientRequestId":"demo-1","message":"查询订单 ORDER-PAID-001 的状态"}'
Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:8090/api/agent/threads/$threadId/items?afterSequence=0&limit=200" -Headers $headers
```

退款或催发货请求只会生成 QuestionCard。批准后命令进入 Worker；可通过 Items 和 SSE 观察 `TOOL_*`、`WORKFLOW_*`、`EXTERNAL_ACTION_STATUS` 和 Turn 终态。

## 排错

- `409 THREAD_AWAITING_ANSWER`：当前 Thread 有开放 QuestionCard，必须先回答、拒绝或取消。
- `429 THREAD_QUEUE_FULL` / `AGENT_QUEUE_FULL`：等待现有 Turn 完成或取消排队请求。
- SSE 断线：先请求 Items API，使用返回的 `nextAfterSequence` 重新订阅 events；最终状态以持久化 Item 为准。
- `MANUAL_RETRY_REQUIRED`：确认外部系统没有成功写入后调用 `/api/agent/workflow-runs/{runId}/retry`，接口保持原幂等键。
- `RUNTIME_RESTARTED`：重启时 ACTIVE Turn 会失败收敛，排队 Turn、QuestionCard 和外部命令继续恢复。

## 验证命令

```text
python -m scripts.convention_check
python -m unittest discover -s scripts/tests -p "test_*.py"
mvn clean '-DskipTests=false' test
cd agent-console
npm run typecheck
npm test -- --run
npm run build
```
