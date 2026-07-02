# Durable After-Sales Agent 运行与验收

## 当前实现

Java 17 主链路已经接通：

```text
INTAKE -> DECIDE_TOOL -> VALIDATE_TOOL -> EXECUTE_TOOL
       -> EVALUATE_POLICY -> READY_FOR_APPROVAL (interrupt)
       -> EXECUTE_REFUND -> VERIFY -> COMPLETED
```

- Spring AI `ChatModel` 只生成 `query_order` 请求，`ToolCallingManager` 在 Graph 节点内执行。
- `userId` 通过 `ToolContext` 传给工具，不进入模型参数；模型只能生成 `orderId`。
- 缺少订单号进入 `NEED_USER_INPUT` interrupt；退款前进入 `READY_FOR_APPROVAL` interrupt。
- resume 必须携带当前 checkpoint ID；旧 checkpoint 返回 HTTP 409。
- 参数修复最多 2 次，瞬态重试最多 2 次，状态重载最多 1 次；相同失败指纹重复时提前终止。
- 退款使用 `caseId:REFUND` 幂等键，订单更新、Command 和 Outbox 在同一 MySQL 事务内完成。
- `agent_run/agent_step_run` 记录业务 Run 与步骤摘要，`LANGRAPH4J_*` 表只负责恢复状态。
- `DECIDE_TOOL`、`EXECUTE_TOOL`、`EXECUTE_REFUND`、`VERIFY` 等阻塞 I/O 节点使用专用有界执行器；Policy Edge 保持同步执行。

## 配置

开发环境默认使用内存 checkpoint，避免默认启动时污染主库核心表；需要验证跨进程恢复时再显式切到 MySQL：

```yaml
ai-agent:
  after-sales:
    checkpoint-store: memory
    model-bean-name: ""
```

- `AI_AGENT_AFTER_SALES_CHECKPOINT_STORE=memory`：本地离线测试。
- `AI_AGENT_AFTER_SALES_CHECKPOINT_STORE=mysql`：跨进程恢复；先执行完整数据库脚本。
- `AI_AGENT_AFTER_SALES_MODEL_BEAN_NAME`：显式指定动态注册的 `ChatModel` Bean；为空时选择可用模型，无模型时仅对明确订单号使用确定性降级。

执行 `docs/dev-ops/mysql/sql/ai-agent-station.sql`，一次创建两张运行表、四张售后业务表和两张 checkpoint 表。

两张运行表和四张售后业务表均使用 MyBatis Mapper；两张 `LANGRAPH4J_*` 表由 LangGraph4j `MysqlSaver` 管理。

`sessionId` 当前只用于把多个 Run 归组，不保存聊天历史，因此没有 Session 消息表或 Turn 表。

## HTTP 流程

启动退款 Run：

```http
POST /api/v1/after-sales/runs
Content-Type: application/json

{
  "userId": "demo-user-1",
  "sessionId": "session-1",
  "message": "申请退款订单 ORDER-PAID-001",
  "orderId": "ORDER-PAID-001",
  "refundReason": "DAMAGED"
}
```

批准当前 checkpoint：

```http
POST /api/v1/after-sales/runs/{runId}/resume
Content-Type: application/json

{
  "checkpointId": "响应中的当前 checkpointId",
  "action": "APPROVE"
}
```

补充订单信息使用 `SUPPLY_INFO`，拒绝退款使用 `REJECT`。查询和取消接口分别为：

- `GET /api/v1/after-sales/runs/{runId}`
- `DELETE /api/v1/after-sales/runs/{runId}`

## 验收

离线聚焦验收：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
mvn -pl ai-agent-station-app -am -DskipTests=false "-Dtest=AfterSalesGraphTest,AfterSalesAgentServiceTest,SpringAiAfterSalesToolAdapterTest,AfterSalesTrajectoryEvaluationTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

八张表建表与读写、MySQL checkpoint、跨实例 resume、幂等 Command 和单条 Outbox 集成测试：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
mvn -pl ai-agent-station-app -am -DskipTests=false -Dit.test=MysqlAfterSalesPersistenceIT verify
```

当前机器已使用 Docker Desktop 与 MySQL 8.0.36 运行 `MysqlAfterSalesPersistenceIT`：第一个 Graph 实例在审批点持久化 checkpoint，第二个 Graph 实例加载同一 checkpoint 并恢复执行，最终断言只生成一条退款 Command、一条 Outbox，订单状态为 `REFUNDED`。

当前版本不把 Java 21 虚拟线程作为已落地亮点；如果后续要升级，需要补充并发压测、连接池容量和运行时线程观测证据。

## 暂不宣称

- 当前没有真实支付系统，只更新本地演示订单。
- Outbox 已建立事务账本，但尚未实现生产消息投递器。
- 未完成真实模型 Full 评测，不提供效果提升百分比。
- 尚未进行生产负载下的连接池、下游限流和并发容量验证。
