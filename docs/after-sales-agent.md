# 售后 Agent 运行与验收

当前架构与恢复语义见 [architecture.md](architecture.md)。本文只说明本地运行、接口和验收。

## 配置

开发环境使用 MySQL 保存 Case、Turn、checkpoint、退款 Command、Outbox/Inbox 与 Session Memory。状态机实例不跨请求驻留。

在 `ai-agent-station-app/.env` 配置 OpenAI 兼容模型：

```properties
SPRING_AI_MODEL_CHAT=openai
OPENAI_BASE_URL=https://api.deepseek.com/v1
OPENAI_API_KEY=<your-api-key>
OPENAI_MODEL=deepseek-v4-pro
AI_AGENT_AFTER_SALES_COMMERCE_ADAPTER=local
AI_AGENT_AFTER_SALES_EVIDENCE_TOOLS=query_order
```

- `OPENAI_MODEL` 只用于 Plan/RePlan。
- `AI_AGENT_AFTER_SALES_EVIDENCE_TOOLS` 声明可用的只读证据能力。默认 `query_order`；HTTP commerce 契约可用后可显式设为 `query_order,query_logistics,query_refund_history`。
- `AI_AGENT_AFTER_SALES_COMMERCE_ADAPTER` 可取 `local` 或 `http`。本地适配器只提供订单证据；HTTP 适配器使用 `/orders/{id}`、`/orders/{id}/logistics`、`/orders/{id}/refund-history` 与 `/refunds`。

执行 [ai-agent-station.sql](dev-ops/mysql/sql/ai-agent-station.sql) 初始化 9 张项目表。

## HTTP 接口

- `POST /api/v1/after-sales/cases`：创建并开始 Case，身份来自 `X-User-Id`。
- `POST /api/v1/after-sales/cases/{caseId}/resume`：补充信息、批准或拒绝；批准要求 `X-User-Role: AFTER_SALES_APPROVER`。
- `GET /api/v1/after-sales/cases/{caseId}`：所有者或审批人查询。
- `DELETE /api/v1/after-sales/cases/{caseId}`：所有者取消。

启动 Case：

```http
POST /api/v1/after-sales/cases
Content-Type: application/json
X-User-Id: demo-user-1

{
  "sessionId": "session-1",
  "message": "申请退款订单 ORDER-PAID-001",
  "orderId": "ORDER-PAID-001",
  "refundReason": "DAMAGED"
}
```

批准当前边界 checkpoint：

```http
POST /api/v1/after-sales/cases/{caseId}/resume
Content-Type: application/json
X-User-Id: approver-1
X-User-Role: AFTER_SALES_APPROVER

{
  "checkpointId": "current-boundary-checkpoint-id",
  "action": "APPROVE"
}
```

补充信息使用 `SUPPLY_INFO`，拒绝使用 `REJECT`。resume 必须携带当前 checkpoint；旧 checkpoint 或并发恢复返回 HTTP 409。

## 验收

离线测试：

```powershell
mvn -pl ai-agent-station-app -am "-DskipTests=false" test
```

Testcontainers MySQL 集成测试要求 Docker Desktop 可用：

```powershell
mvn -pl ai-agent-station-app -am "-DskipTests=false" "-Dit.test=MysqlAfterSalesPersistenceIT" verify
```

完整默认验收会运行离线测试与 MySQL 集成测试；真实模型、模型连通性和并发基准均由显式 Maven `-D` 开关控制：

```powershell
mvn -pl ai-agent-station-app -am "-DskipTests=false" verify
mvn -pl ai-agent-station-app -am "-DskipTests=false" "-Dit.test=AfterSalesLiveModelEvaluationIT" "-Dlive.after-sales.evaluation.enabled=true" verify
```

真实模型与并发结果见 [evaluation](evaluation/)。生产边界和未完成项见 [architecture.md](architecture.md)。
