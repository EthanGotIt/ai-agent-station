# 独立 HTTP 订单服务夹具

这是一个只用于本机验收的独立订单服务，不是生产订单平台。它运行在单独进程中，使用独立 SQLite 数据文件，和 Commerce Guardian Agent 的 MySQL 没有共享数据。

服务提供：

- `GET /orders/search`
- `GET /orders/{orderId}`
- `GET /orders/{orderId}/logistics`
- `POST /orders/{orderId}/refund`
- `POST /orders/{orderId}/expedite`
- `DELETE /orders/{orderId}`

查询和写操作都要求 `X-User-Id`。写操作还要求 `Idempotency-Key`；服务端将 `(userId, idempotencyKey)`、请求摘要、响应和业务变更标记持久化在 SQLite 中，重复请求返回原响应，不重复修改订单。删除订单时同步删除物流轨迹；`/_fixture/stats` 仅用于本机验收，查看幂等记录、实际业务变更次数和注入的临时失败次数。

为验证自动重试耗尽和人工重试，可在启动夹具前设置
`ORDER_SERVICE_FIXTURE_EXPEDITE_TRANSIENT_FAILURES=3`。该值只对有效的待发货催发货订单生效：前 3 次使用同一幂等键返回 `retryable=true`，不创建幂等记录、不改变订单；后续请求才执行一次真实变更。默认值为 `0`，不注入故障。

## 本地进程启动

夹具只用于本机 HTTP 验收，不需要 Docker 或部署环境。完整黄金路径由
`scripts/review/review-services.ps1` 直接启动 `server.py`；需要单独运行时，
在仓库根目录执行：

```powershell
python scripts/acceptance/order_service_fixture/server.py
```

直接运行时默认监听 `127.0.0.1:18080`，数据库写入仓库根目录下的 `.runtime/order-service.db`；如需临时调整，可通过 `ORDER_SERVICE_HOST`、`ORDER_SERVICE_PORT` 和 `ORDER_SERVICE_DATABASE_PATH` 覆盖。

## Agent 配置

不要把真实凭据写入仓库。启动 Agent 前，在当前 PowerShell 进程设置：

```powershell
$env:AI_AGENT_ORDER_GATEWAY = 'http'
$env:AI_AGENT_ORDER_BASE_URL = 'http://127.0.0.1:18080'
$env:AI_AGENT_ORDER_HTTP_TIMEOUT = 'PT5S'
```

如果通过 `commerce-guardian-agent-app/.env` 加载配置，将 `AI_AGENT_ORDER_GATEWAY` 改为 `http`、将 `AI_AGENT_ORDER_BASE_URL` 改为 `http://127.0.0.1:18080`；验收结束后可恢复为 `local`。夹具只在本机进程中运行，不对公网提供服务。

默认演示身份为 `demo-user-1`，初始订单包括：

- `ORDER-EXT-TODAY-001`：今天、已支付、待发货；
- `ORDER-EXT-STALLED-001`：已发货、物流三天未更新；
- `ORDER-EXT-DELIVERED-001`：已签收；
- `ORDER-EXT-REFUND-001`：可走退款确认链路。

服务端没有生产级登录系统；`X-User-Id` 仅用于验证当前 Agent 的身份边界。不要把此夹具暴露到公网。
