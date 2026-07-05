# Durable After-Sales Agent 升级总控

## 当前状态

- 当前分支：`codex/durable-after-sales-agent`，基线提交 `35a7af6`。
- Java 17 主链路已升级为轻量 Plan-and-Execute：Spring AI `ChatClient` 生成 JSON Plan，`RefundInformationGatherer` 执行并最多 3 次 RePlan，Spring State Machine 只守护 `INTAKE / PENDING_APPROVAL / COMPLETED / REJECTED` 状态。
- 28 条单元测试、30 条真实模型冻结轨迹和 Testcontainers MySQL 集成验收覆盖主链路。
- 8 张自有表统一使用 MyBatis，单一 SQL 脚本创建 8 表结构。
- Java 17 并发基线 431.03 tasks/s、P95 82 ms、0 错误，当前决定不升级 Java 21。
- 本文是目标实现约束，不得在代码和验收完成前写成简历中的“已实现能力”。

当前属于可运行、可测试、可恢复的工程 MVP，即“初步落地”；尚不能称为生产完成版。

## 项目定位

> 基于 Spring AI 2、Spring State Machine 与 spring-ai-community 的可恢复售后退款 Agent。模型负责信息收集规划（Plan），Java 负责执行、校验和守护金融副作用边界（Execute）；Policy 负责状态转移、错误恢复、人工审批和副作用安全。

第一版只覆盖退款主链路：订单识别、Plan 生成与执行、RePlan、资格校验、补充信息、审批、幂等退款、通知和结果核验。不做换货、补偿、多 Agent、长期记忆或通用工作流平台。

## 技术边界

| 层次 | 负责 | 明确不负责 |
|---|---|---|
| Spring AI 2 / spring-ai-community | `ChatClient` Plan 生成、`ToolCallback`/`ToolCallingManager` 只读订单查询、`SessionMemoryAdvisor` 会话记忆、`TodoWriteTool` 任务清单 | 不拥有售后状态机，不自动决定 RePlan、审批或终止 |
| Spring State Machine | 轻量状态图（`INTAKE / PENDING_APPROVAL / COMPLETED / REJECTED`）、事件/Guard/Action、内存 checkpoint、interrupt/resume | 不替代模型 SDK 或 Plan 执行器，不承载业务资格和风险规则 |
| 项目代码 | `RefundPlanningAgent`、`RefundInformationGatherer`、`AfterSalesAgentState`、Policy Edge、RePlan 预算、幂等 Command、审批、Outbox、轨迹评测 | 不重复实现模型 SDK、通用图执行器或 checkpoint 存储引擎 |
| Java 17（当前） | 承载业务图，并用专用有界执行器隔离阻塞 I/O 节点 | 不替代连接池、限流、超时、bulkhead 或 Reactor |

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

## Java 21 与虚拟线程暂缓边界

- 持久化 checkpoint、审批恢复、幂等退款、Outbox 和 MySQL 跨实例恢复在 Java 17 下完成验收。
- 当前不全局启用 `spring.threads.virtual.enabled=true`，也不把 Java 21 作为当前分支基线。
- 同步 `ChatModel.call()`、JDBC 查询、同步 HTTP ToolCallback 和退款核验等阻塞 I/O 节点使用 Java 17 有界 `agentIoExecutor`。
- Policy、参数校验、状态归并等短 CPU 节点不切换线程；`ChatModel.stream()` 和 Reactor 链保持响应式执行。
- 如果后续升级 Java 21，每个 `@Transactional` Command 仍必须在同一执行线程中完整执行，禁止在事务内部再并行拆分任务。
- 即使后续使用虚拟线程，也仍要保留下游并发上限、连接池、超时和 bulkhead；生产负载下的线程观测和容量压测必须单独验证。
- 当前 Maven Enforcer 要求 Java 17+。

## 分阶段落地

| 阶段 | 范围 | 当前状态 |
|---|---|---|
| Phase 0 | Java 17 状态图骨架、资格 Policy、错误恢复预算、Spring State Machine 内存状态机、离线单测 | 已完成轻量验收 |
| Phase 1 | Spring AI 低层 Tool Calling、只读订单工具、结构化错误分类 | 代码与离线测试完成 |
| Phase 2 | MySQL checkpoint、补信息/审批 interrupt-resume、过期 checkpoint 防护 | 已完成；Testcontainers 验证跨实例恢复 |
| Phase 3 | 幂等退款 Command、Outbox、宕机与重复恢复测试 | 已完成；投递租约、退避重试和 Inbox 幂等已接通 |
| Phase 4 | 冻结轨迹集、基线对比、故障注入和完整验收 | 已完成；真实模型 Plan 契约与治理路由均 30/30 |
| Phase 5 | Java 21、虚拟线程、并发边界和运行时观测 | Java 17 基线完成，当前不升级 Java 21 |
| Phase 7 | 主链路升级为轻量 Plan-and-Execute：3 状态 SSM、`RefundPlanningAgent` JSON Plan、`RefundInformationGatherer` + 最多 3 次 RePlan、`TodoWriteTool` 检查清单 | 已完成；`mvn clean test` 28 条单测通过，真实模型与并发基准已更新 |

## 后续生产联调优先级

1. 将 HTTP commerce adapter 对接真实订单/退款测试环境，并使用网关验证过的身份替代开发请求头。
2. 将本地事件 Publisher 替换为生产 MQ，保留现有 Outbox/Inbox 语义。
3. 接入 Micrometer tracing 后端，固化 Model/Tool Token、错误率和 Run/Step 链路查询。
4. 使用真实数据库、HTTP 和连接池容量做长时间压测；只有平台线程成为瓶颈时才复测 Java 21。

## 公共接口与持久化

- `POST /api/v1/after-sales/cases`：启动 Case，身份来自 `X-User-Id`。
- `POST /api/v1/after-sales/cases/{caseId}/resume`：补信息或审批；审批要求 `AFTER_SALES_APPROVER`。
- `GET /api/v1/after-sales/cases/{caseId}`：所有者或审批人查询。
- `DELETE /api/v1/after-sales/cases/{caseId}`：仅所有者取消。
- `docs/dev-ops/mysql/sql/ai-agent-station.sql` 统一创建运行审计、演示订单、售后 Case、幂等 Command、Outbox 和业务快照表。
- `sessionId` 只是归组标识；`caseId` 同时作为 Spring State Machine 的状态机标识（thread key），审计层为 Case → Turn → Run → Step。

## 评测与验收

- 冻结 30 条轨迹：12 条正常退款、8 条补信息/规则拒绝、10 条参数错误、超时、状态冲突、宕机恢复和重复 resume。
- 对比 `UNGOVERNED_TOOL_LOOP` 与 `DURABLE_POLICY_GRAPH`，记录任务成功率、首轮参数合法率、错误恢复率、审批遵从率、重复副作用率、恢复一致性、模型调用数和 P95 延迟。
- 硬门槛：未审批退款、跨用户退款和重复退款均为 0；恢复用例结果一致；不可恢复错误不得进入无效重试。
- 默认单测必须离线；MySQL Testcontainers 覆盖 checkpoint、并发审批、事务幂等、Outbox 和故障恢复；真实模型评测单独门控。
- 没有有效 API Key 时不阻塞代码验收，但不得宣称 Agent 效果提升。
