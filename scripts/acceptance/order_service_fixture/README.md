# 独立 HTTP 订单服务夹具

这是一个只用于本机验收的独立订单服务，不是生产订单平台。它运行在单独进程或 Docker 容器中，使用独立 SQLite 数据卷，和 Commerce Guardian Agent 的 MySQL 没有共享数据。

服务提供：

- `GET /orders/search`
- `GET /orders/{orderId}`
- `GET /orders/{orderId}/logistics`
- `POST /orders/{orderId}/refund`
- `POST /orders/{orderId}/expedite`
- `POST /orders/{orderId}/visibility`

查询和写操作都要求 `X-User-Id`。写操作还要求 `Idempotency-Key`；服务端将 `(userId, idempotencyKey)`、请求摘要、响应和业务变更标记持久化在 SQLite 中，重复请求返回原响应，不重复修改订单。`/_fixture/stats` 仅用于本机验收，查看幂等记录、实际业务变更次数和注入的临时失败次数。

为验证自动重试耗尽和人工重试，可在启动夹具前设置
`ORDER_SERVICE_FIXTURE_EXPEDITE_TRANSIENT_FAILURES=3`。该值只对有效的待发货催发货订单生效：前 3 次使用同一幂等键返回 `retryable=true`，不创建幂等记录、不改变订单；后续请求才执行一次真实变更。默认值为 `0`，不注入故障。

## Docker Desktop 启动

在仓库根目录执行：

```powershell
docker build -f scripts/acceptance/order_service_fixture/Dockerfile -t commerce-guardian-agent-order-service-fixture .
docker volume create commerce-guardian-order-data
docker run --name commerce-guardian-order-service --detach --publish 18080:8080 --volume commerce-guardian-order-data:/data commerce-guardian-agent-order-service-fixture
Invoke-RestMethod -Uri http://127.0.0.1:18080/health
```

停止容器但保留独立订单数据：

```powershell
docker stop commerce-guardian-order-service
docker rm commerce-guardian-order-service
```

只有需要重新生成演示订单时才删除 `commerce-guardian-order-data` 数据卷；删除前应确认其中没有需要保留的验收数据。

## Agent 配置

不要把真实凭据写入仓库。启动 Agent 前，在当前 PowerShell 进程设置：

```powershell
$env:AI_AGENT_ORDER_GATEWAY = 'http'
$env:AI_AGENT_ORDER_BASE_URL = 'http://127.0.0.1:18080'
$env:AI_AGENT_ORDER_HTTP_TIMEOUT = 'PT5S'
```

如果通过 `commerce-guardian-agent-app/.env` 加载配置，将 `AI_AGENT_ORDER_GATEWAY` 改为 `http`、将 `AI_AGENT_ORDER_BASE_URL` 改为 `http://127.0.0.1:18080`；验收结束后可恢复为 `local`。宿主机运行 Agent 时使用 `127.0.0.1`；如果 Agent 也运行在 Docker Compose 中，则应使用同一网络中的服务名，而不是 `localhost`。

默认演示身份为 `demo-user-1`，初始订单包括：

- `ORDER-EXT-TODAY-001`：今天、已支付、待发货；
- `ORDER-EXT-STALLED-001`：已发货、物流三天未更新；
- `ORDER-EXT-DELIVERED-001`：已签收；
- `ORDER-EXT-REFUND-001`：可走退款确认链路。

服务端没有生产级登录系统；`X-User-Id` 仅用于验证当前 Agent 的身份边界。不要把此夹具暴露到公网。
