# Durable After-Sales Agent 升级总控

## 当前状态

- 当前分支：`codex/durable-after-sales-agent`，基线提交 `35a7af6`。
- Java 17 主链路已升级为轻量 Plan-and-Execute：Spring AI `ChatClient` 生成 JSON Plan，`RefundInformationGatherer` 执行并最多 3 次 RePlan，Spring State Machine 只守护 `INTAKE / PENDING_APPROVAL / COMPLETED / REJECTED` 状态。
- 离线测试、30 条真实模型冻结轨迹和 Testcontainers MySQL 集成测试覆盖主链路。
- 单一 SQL 脚本创建 7 张业务表与 2 张 Spring AI Session 表。
- 本文是目标实现约束，不得在代码和验收完成前写成简历中的“已实现能力”。

当前属于可运行、可测试、可恢复的工程 MVP，即“初步落地”；尚不能称为生产完成版。

## 项目定位

> 基于 Spring AI 2、Spring State Machine 与 spring-ai-community 的可恢复售后退款 Agent。模型负责信息收集规划（Plan），Java 负责执行、校验和守护金融副作用边界（Execute）；Policy 负责状态转移、错误恢复、人工审批和副作用安全。

第一版只覆盖退款主链路：订单识别、Plan 生成与执行、RePlan、资格校验、补充信息、审批、幂等退款、通知和结果核验。不做换货、补偿、多 Agent、长期记忆或通用工作流平台。

## 技术边界

| 层次 | 负责 | 明确不负责 |
|---|---|---|
| Spring AI 2 / spring-ai-community | `ChatClient` Plan 生成、`ToolCallback`/`ToolCallingManager` 只读订单查询、Case 级 `SessionMemoryAdvisor`、`TodoWriteTool` 任务清单 | 不拥有售后状态机，不自动决定 RePlan、审批或终止，不保存业务事实 |
| Spring State Machine | 轻量状态图（`INTAKE / PENDING_APPROVAL / COMPLETED / REJECTED`）、事件/Guard/Action、按 `ssm_state` 恢复 | 不替代模型 SDK 或 Plan 执行器，不承载业务资格和风险规则 |
| 项目代码 | `RefundPlanningAgent`、`RefundInformationGatherer`、`AfterSalesAgentState`、Policy Edge、RePlan 预算、幂等 Command、审批、Outbox、轨迹评测 | 不重复实现模型 SDK、通用图执行器或 checkpoint 存储引擎 |

主链路采用 Plan-and-Execute：`RefundPlanningAgent` 输出 JSON Plan，`RefundInformationGatheringPolicy` 校验白名单与收敛性，Java 显式执行并守护最多 3 次 RePlan；Spring State Machine 只控制审批与终态转移。禁用 `ToolCallingAdvisor` 自动循环，避免 Spring AI 和 Spring State Machine 同时拥有控制流。

## 可恢复治理图

```text
INTAKE (Plan-and-Execute)
  ├─ Plan → Execute → RePlan（最多 3 次）
  ├─ 缺少信息 → NEED_USER_INPUT（interrupt）
  └─ 信息完整且通过 Policy → PENDING_APPROVAL
PENDING_APPROVAL
  ├─ APPROVE → 幂等退款 → COMPLETED
  └─ REJECT / 资格不符 → REJECTED
```

- 缺少订单号或必要信息时，由 `RefundPlanningAgent` 生成 `ASK_USER` 步骤，在 `INTAKE` 内设置 `NEED_USER_INPUT` interrupt。
- 退款执行前进入 `PENDING_APPROVAL` interrupt，恢复请求必须携带当前 checkpoint ID。
- `RefundInformationGatheringPolicy` 校验 Plan 动作白名单（`ASK_USER` / `TOOL_CALL`）与工具白名单（`query_order`），非法 Plan 被替换为确定性兜底。
- 工具失败或信息不完整触发 RePlan，最多 3 次；超过预算或业务拒绝（跨用户、状态不可退）直接进入 `REJECTED`。
- 相同 input fingerprint 连续失败时提前终止，禁止 Reflection 死循环。
- 退款 Command 使用 `caseId:REFUND` 作为业务幂等键；远程退款不跨网络持有数据库事务，成功后再事务性确认 Command、Case 和 Outbox。

## 分阶段落地

| 阶段 | 范围 | 当前状态 |
|---|---|---|
| Phase 0 | 状态图骨架、资格 Policy、错误恢复预算、离线单测 | 已完成轻量验收 |
| Phase 1 | Spring AI 低层 Tool Calling、只读订单工具、结构化错误分类 | 代码与离线测试完成 |
| Phase 2 | MySQL 过程快照与 Turn 边界、按 `ssm_state` 恢复、过期 checkpoint 与租约防护 | 已完成代码与测试覆盖 |
| Phase 3 | 幂等退款 Command、Outbox、宕机与重复恢复测试 | 已完成；投递租约、退避重试和 Inbox 幂等已接通 |
| Phase 4 | 冻结轨迹集、基线对比、故障注入和完整验收 | 已完成；真实模型 Plan 契约与治理路由均 30/30 |
| Phase 5 | Micrometer 运行指标 | 已覆盖 Model、Tool、RePlan、checkpoint、恢复冲突、Turn 边界与退款结果 |
| Phase 7 | 主链路升级为轻量 Plan-and-Execute：4 状态 SSM、`RefundPlanningAgent` JSON Plan、`RefundInformationGatherer` + 最多 3 次 RePlan、`TodoWriteTool` 检查清单 | 已完成；离线测试与真实模型评测已更新 |

## 后续生产联调优先级

1. 将 HTTP commerce adapter 对接真实订单/退款测试环境，并使用网关验证过的身份替代开发请求头。
2. 将本地事件 Publisher 替换为生产 MQ，保留现有 Outbox/Inbox 语义。
3. 接入 Micrometer 监控后端，展示 Model/Tool 延迟、RePlan、checkpoint、恢复冲突和退款结果。
4. 使用真实数据库、HTTP 和连接池容量做长时间压测。

## 公共接口与持久化

- `POST /api/v1/after-sales/cases`：启动 Case，身份来自 `X-User-Id`。
- `POST /api/v1/after-sales/cases/{caseId}/resume`：补信息或审批；审批要求 `AFTER_SALES_APPROVER`。
- `GET /api/v1/after-sales/cases/{caseId}`：所有者或审批人查询。
- `DELETE /api/v1/after-sales/cases/{caseId}`：仅所有者取消。
- `docs/dev-ops/mysql/sql/ai-agent-station.sql` 统一创建运行审计、演示订单、售后 Case、幂等 Command、Outbox 和业务快照表。
- `sessionId` 只是归组标识；`caseId` 同时作为 Spring State Machine 的 thread key 与模型记忆键，审计层为 Case → Turn → Checkpoint。

## 评测与验收

- 冻结 30 条轨迹：12 条正常退款、8 条补信息/规则拒绝、10 条参数错误、超时、状态冲突、宕机恢复和重复 resume。
- 对比 `UNGOVERNED_TOOL_LOOP` 与 `DURABLE_POLICY_GRAPH`，记录任务成功率、首轮参数合法率、错误恢复率、审批遵从率、重复副作用率、恢复一致性、模型调用数和 P95 延迟。
- 硬门槛：未审批退款、跨用户退款和重复退款均为 0；恢复用例结果一致；不可恢复错误不得进入无效重试。
- 默认单测必须离线；Testcontainers 临时 MySQL 覆盖 checkpoint、并发审批、事务幂等、Outbox 和故障恢复；真实模型评测单独门控。
- 没有有效 API Key 时不阻塞代码验收，但不得宣称 Agent 效果提升。
