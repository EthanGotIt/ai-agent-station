# Durable After-Sales Agent 运行与验收

## 当前实现

Java 17 主链路已升级为轻量 **Plan-and-Execute** 范式：

```text
INTAKE (Plan-and-Execute)
  ├─ Plan → Execute → RePlan（最多 3 次）
  ├─ 缺少信息 → NEED_USER_INPUT（interrupt）
  └─ 信息完整且通过 Policy → PENDING_APPROVAL
PENDING_APPROVAL
  ├─ APPROVE → 幂等退款 → COMPLETED
  └─ REJECT / 资格不符 → REJECTED
```

- `RefundPlanningAgent`（Spring AI `ChatClient` + `SessionMemoryAdvisor`）根据当前上下文输出 JSON Plan，只规划还需要收集的信息；不决定退款、不生成副作用动作。
- `RefundInformationGatherer` 执行 Plan：
  - `ASK_USER` → 在 `INTAKE` 阶段设置 `NEED_USER_INPUT` interrupt；
  - `TOOL_CALL(query_order)` → 调用 `SpringAiAfterSalesToolAdapter` 查询订单；可按运行时能力显式启用 `query_logistics` 与 `query_refund_history`；
  - 工具失败或信息仍不完整时触发 RePlan，最多 3 次，超过则进入 `REJECTED`。
- `SpringAiAfterSalesToolAdapter` 不再调用模型：它只执行已通过 Policy 的计划步骤，并以服务端 `caseId / userId / turnId` 上下文访问只读 commerce 证据；模型不能自行构造身份或执行退款动作。
- `RefundInformationGatheringPolicy` 硬拦截 Plan：校验 schema、证据缺口、动作白名单（`ASK_USER` / `TOOL_CALL`）和当前运行时声明的工具集合，禁止重复询问已存在字段。
- 信息完整后由 `AfterSalesRefundEligibilityPolicy` 判定资格，进入 `PENDING_APPROVAL`；进入审批时 `TodoWriteTool` 生成退款检查清单并写入业务状态。
- 状态机只保留 `INTAKE / PENDING_APPROVAL / COMPLETED / REJECTED` 四个业务状态，并从数据库中的 `ssm_state` 与业务状态恢复。
- `userId` 来自 HTTP 身份头并通过 `ToolContext` 传给工具，不进入模型参数；模型只能生成 `orderId`。
- resume 必须携带当前 checkpoint ID；旧 checkpoint 或并发恢复返回 HTTP 409。
- 退款使用 `caseId:REFUND` 幂等键：事务内准备 Command，事务外调用退款适配器，再在事务内确认 Command、Case 和 Outbox；宕机重放仍复用同一幂等键。
- `after_sales_case -> agent_turn -> agent_checkpoint` 分别记录业务流程、状态机运行尝试和 Plan/Tool/Policy 执行快照；只有 Case 指向的 Turn 边界 checkpoint 可用于外部恢复。
- `caseId` 同时作为 Spring State Machine 的状态机标识（thread key），resume 使用数据库条件租约防止同一 checkpoint 被并发消费。
- Outbox 使用领取租约、指数退避和最大重试，Inbox 以 `eventId + consumerName` 保证消费幂等。

## 配置

开发环境使用 MySQL 保存过程快照和 Turn 边界；状态机实例不跨请求驻留。

模型提供方已切换为 **DeepSeek**（OpenAI 兼容协议），并在 `ai-agent-station-app/.env` 中配置：

```properties
SPRING_AI_MODEL_CHAT=openai
OPENAI_BASE_URL=https://api.deepseek.com/v1
OPENAI_API_KEY=<你的 DeepSeek API key>
OPENAI_MODEL=deepseek-v4-pro
AI_AGENT_AFTER_SALES_EVIDENCE_TOOLS=query_order
```

- `OPENAI_MODEL=deepseek-v4-pro`：Plan / RePlan 阶段模型，由 `RefundPlanningAgent` 使用。
- `AI_AGENT_AFTER_SALES_EVIDENCE_TOOLS=query_order`：声明允许执行的只读证据工具。HTTP commerce 契约可用后可显式设为 `query_order,query_logistics,query_refund_history`；本地适配器只支持 `query_order`。
- `AI_AGENT_AFTER_SALES_COMMERCE_ADAPTER=local|http`：本地演示订单或真实 HTTP 订单/退款适配器。

执行 `docs/dev-ops/mysql/sql/ai-agent-station.sql`，一次创建 9 张项目表。

业务表使用 MyBatis Mapper；`AI_SESSION` 与 `AI_SESSION_EVENT` 由 spring-ai-session JDBC Repository 访问。

`sessionId` 只作为调用方归组字段；`caseId` 同时作为模型记忆键和状态机 thread key，避免同一调用方会话中的不同售后 Case 串扰。

## HTTP 流程

启动退款 Run：

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

批准当前 checkpoint：

```http
POST /api/v1/after-sales/cases/{caseId}/resume
Content-Type: application/json
X-User-Id: approver-1
X-User-Role: AFTER_SALES_APPROVER

{
  "checkpointId": "响应中的当前 checkpointId",
  "action": "APPROVE"
}
```

补充订单信息使用 `SUPPLY_INFO`，拒绝退款使用 `REJECT`。查询和取消接口分别为：

- `GET /api/v1/after-sales/cases/{caseId}`
- `DELETE /api/v1/after-sales/cases/{caseId}`

## 验收

离线聚焦验收：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
mvn -pl ai-agent-station-app -am "-DskipTests=false" "-Dtest=AfterSalesGraphTest,AfterSalesAgentServiceTest,SpringAiAfterSalesToolAdapterTest,AfterSalesTrajectoryEvaluationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

9 张表建表与读写、边界 checkpoint、过期租约接管、跨实例 resume、幂等 Command、Outbox 重试与 Inbox 幂等消费集成测试：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
mvn -pl ai-agent-station-app -am "-DskipTests=false" "-Dit.test=MysqlAfterSalesPersistenceIT" verify
```

真实模型与 Java 17 并发指标见 `docs/evaluation/`。2026-07-10 真实模型 30/30 Plan 契约与治理路由通过；Java 17 基线 431.03 tasks/s、P95 82 ms、0 错误。

## 暂不宣称

- 已提供 HTTP 订单/退款适配器，但尚未与生产服务联调。
- 当前 Outbox 默认使用本地幂等消费者；尚未接入生产 MQ。
- 尚未进行生产连接池、下游限流和真实网络容量验证。
