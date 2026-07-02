# Durable After-Sales Agent

基于 Spring AI 2 与 LangGraph4j 的可恢复售后退款 Agent。模型只负责理解用户意图并生成受限的只读工具请求；Java 状态图和 Policy 负责流程推进、错误恢复、人工审批以及退款副作用安全。

当前是完成主链路和恢复验收的工程 MVP，属于初步落地，不代表已完成真实支付接入、消息投递和生产容量验证。

## 技术基线

- Java 17、Spring Boot 4.1、Spring AI 2
- LangGraph4j 1.8
- MySQL、MyBatis
- Testcontainers

当前不把 Java 21 或虚拟线程作为项目亮点。阻塞模型调用和 JDBC 节点由专用有界线程池隔离，后续只有在并发压测证明收益后才考虑升级。

## 主链路

```text
INTAKE
-> DECIDE_TOOL
-> VALIDATE_TOOL
-> EXECUTE_TOOL
-> EVALUATE_POLICY
-> READY_FOR_APPROVAL (interrupt)
-> EXECUTE_REFUND
-> VERIFY
-> COMPLETED
```

- Spring AI：`ChatModel + ToolCallingManager + ToolCallback`，只负责模型与工具调用协议。
- LangGraph4j：状态图、checkpoint、interrupt/resume。
- Java Policy：退款资格、参数修复、有限重试、审批、幂等和终止条件。

缺少订单号时进入补充信息 interrupt；退款执行前必须进入人工审批 interrupt。恢复请求必须携带当前 checkpoint ID，旧 checkpoint 返回 HTTP 409。

## Session、Run 与 checkpoint

- `sessionId`：调用方提供的业务归组标识，可关联多个 Run；当前不保存聊天历史或长期记忆。
- `runId`：一次可查询、可取消、可恢复的售后执行，记录在 `agent_run`。
- `agent_step_run`：对外可观测的业务步骤摘要。
- `LANGRAPH4J_THREAD/LANGRAPH4J_CHECKPOINT`：框架恢复状态，不替代 Run 审计。

因此当前模型是 **Session 标识 + Run 持久化**，没有单独的 Turn 表，也没有冗余 Session 消息表。

## 数据库

执行唯一的数据库初始化脚本：

```powershell
Get-Content -Raw .\docs\dev-ops\mysql\sql\ai-agent-station.sql |
  & 'D:\Environment\MySQL\bin\mysql.exe' -h 127.0.0.1 -P 3306 -u root -p ai-agent-station
```

最终只保留 8 张表：

- 通用运行态：`agent_run`、`agent_step_run`
- 售后业务：`demo_order`、`after_sales_case`、`refund_command`、`after_sales_outbox`
- LangGraph4j：`LANGRAPH4J_THREAD`、`LANGRAPH4J_CHECKPOINT`

其中 6 张项目自有表统一通过 MyBatis Mapper 访问；两张 `LANGRAPH4J_*` 表由 `MysqlSaver` 管理。

## HTTP

- `POST /api/v1/after-sales/runs`
- `POST /api/v1/after-sales/runs/{runId}/resume`
- `GET /api/v1/after-sales/runs/{runId}`
- `DELETE /api/v1/after-sales/runs/{runId}`

示例：

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

## 验收

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl ai-agent-station-app -am "-DskipTests=false" test
```

跨实例 checkpoint、重复恢复、事务幂等和 Outbox：

```powershell
mvn -pl ai-agent-station-app -am "-DskipTests=false" -Dit.test=MysqlAfterSalesPersistenceIT verify
```

详细边界与验收说明见：

- `docs/after-sales-agent.md`
- `docs/agent-runtime-upgrade-plan.md`
- `docs/agent-runtime-resume-defense.md`
