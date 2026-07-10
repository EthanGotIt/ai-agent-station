# Durable After-Sales Agent

基于 Spring AI 2、Spring State Machine 与 spring-ai-community 的可恢复售后退款 Agent。模型负责规划信息收集（Plan），Java 负责执行、校验和守护金融副作用边界（Execute）；状态机和 Policy 负责流程推进、错误恢复、人工审批以及退款副作用安全。

当前已完成 Plan-and-Execute 主链路、恢复、真实模型评估、Outbox/Inbox 和并发基线验收，仍未与生产订单、退款服务实际联调。

## 技术基线

- Java 17、Spring Boot 4.1、Spring AI 2
- Spring State Machine 4.0（默认运行时）
- spring-ai-community：`spring-ai-session-management`、`spring-ai-agent-utils`
- MySQL、MyBatis
- Testcontainers MySQL（仅集成测试）

## 主链路

```text
INTAKE (Plan-and-Execute)
  ├─ Plan → Execute → RePlan（最多 3 次）
  ├─ 缺少信息 → NEED_USER_INPUT（interrupt，补充后继续）
  └─ 信息完整且通过 Policy → PENDING_APPROVAL
PENDING_APPROVAL
  ├─ APPROVE → 幂等退款 → COMPLETED
  └─ REJECT / 资格不符 → REJECTED
```

- Spring AI `ChatClient`：通过 `RefundPlanningAgent` 生成结构化的信息收集计划（JSON Plan）。
- `RefundInformationGatherer`：执行 Plan 中的 `ASK_USER` / `TOOL_CALL(query_order)` 步骤，监控执行结果并触发最多 3 次 RePlan。
- Spring State Machine：业务状态图只保留 `INTAKE / PENDING_APPROVAL / COMPLETED / REJECTED`，interrupt/resume 与 checkpoint 语义由 `IAfterSalesStateMachine` 端口封装。
- spring-ai-session：通过 `SessionMemoryAdvisor` 保存 Case 级规划对话；业务状态仍以 checkpoint 和业务表为准。
- spring-ai-agent-utils：在进入 `PENDING_APPROVAL` 时通过 `TodoWriteTool` 生成退款检查清单。
- Java Policy：`RefundInformationGatheringPolicy` 校验 Plan 动作白名单与收敛性；`AfterSalesRefundEligibilityPolicy` 判定退款资格；`AfterSalesAuthorizationService` 守护审批身份。

缺少订单号时进入补充信息 interrupt；退款执行前必须进入人工审批 interrupt。恢复请求必须携带当前 checkpoint ID，旧 checkpoint 返回 HTTP 409。

## Case、Turn 与 checkpoint

- `sessionId`：调用方提供的归组标识，不作为模型记忆键。
- `caseId`：跨多轮的售后业务流程，同时作为状态机 `threadId`。
- `turnId`：一次状态机 start/resume/retry 尝试，也是一次用户补充或人工审批交互。
- `agent_checkpoint`：保存 Plan、Tool、Policy 的过程快照及 Turn 边界。
- `after_sales_case.checkpoint_id`：唯一已提交、可由外部 resume 的 checkpoint 指针。

因此当前模型是 **Session 标识 + Case → Turn → Checkpoint**；业务 `caseId` 直接复用为状态机 thread key。

## 模块划分

- `ai-agent-station-types`：共享内核，包含强类型 ID 与基础异常。
- `ai-agent-station-domain`：领域模型、端口、Policy、领域服务。
- `ai-agent-station-infrastructure`：Spring State Machine、Spring AI、仓库、网关、事件适配器。
- `ai-agent-station-trigger`：HTTP 触发器与 DTO。
- `ai-agent-station-app`：应用启动与配置。

## 数据库

执行唯一的数据库初始化脚本：

```powershell
Get-Content -Raw .\docs\dev-ops\mysql\sql\ai-agent-station.sql |
  & 'D:\Environment\MySQL\bin\mysql.exe' -h 127.0.0.1 -P 3306 -u root -p ai-agent-station
```

共 9 张项目表：

- 运行审计：`agent_turn`、`agent_checkpoint`
- 售后业务：`demo_order`、`after_sales_case`、`refund_command`
- 可靠事件：`after_sales_outbox`、`after_sales_event_consume`
- 模型记忆：`AI_SESSION`、`AI_SESSION_EVENT`

业务表统一通过 MyBatis Mapper 访问。状态机按 `ssm_state` 和完整业务状态从数据库 checkpoint 重建；过程快照不会覆盖 Case 已提交的恢复指针。

## HTTP

- `POST /api/v1/after-sales/cases`
- `POST /api/v1/after-sales/cases/{caseId}/resume`
- `GET /api/v1/after-sales/cases/{caseId}`
- `DELETE /api/v1/after-sales/cases/{caseId}`

示例：

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

## 验收

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl ai-agent-station-app -am "-DskipTests=false" test
```

使用 Testcontainers 临时 MySQL 验证跨实例 checkpoint、重复恢复、事务幂等和 Outbox；仅该命令要求 Docker：

```powershell
mvn -pl ai-agent-station-app -am "-DskipTests=false" "-Dit.test=MysqlAfterSalesPersistenceIT" verify
```

详细边界与验收说明见：

- `docs/refactoring-plan.md`：本次从 LangGraph4j 迁移到 Spring State Machine + spring-ai-community 的重构计划。
- `docs/after-sales-agent.md`
- `docs/agent-runtime-upgrade-plan.md`
- `docs/agent-runtime-resume-defense.md`
