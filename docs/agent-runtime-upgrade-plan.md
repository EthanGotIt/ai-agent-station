# Durable After-Sales Agent 升级总控

## 当前状态

- 当前分支：`codex/durable-after-sales-agent`，基线提交 `f3c8541`。
- Java 17 主链路代码已落地：Spring AI 低层 Tool Calling、LangGraph4j interrupt/resume、MysqlSaver、checkpoint 版本校验、幂等退款 Command 和 Outbox 已接入。
- 17 条售后聚焦测试、冻结 30 条确定性轨迹和 1 条 Testcontainers MySQL 跨实例恢复测试已通过。
- 6 张自有表已统一使用 MyBatis，单一 SQL 脚本创建完整 8 表结构。
- 当前保持 Java 17；阻塞 I/O 节点接入专用有界执行器。Java 21/虚拟线程作为后续可选优化，不作为当前亮点。
- 本文是目标实现约束，不得在代码和验收完成前写成简历中的“已实现能力”。

当前属于可运行、可测试、可恢复的工程 MVP，即“初步落地”；尚不能称为生产完成版。

## 项目定位

> 基于 Spring AI 2 与 LangGraph4j 的可恢复售后退款 Agent。模型负责理解自然语言和生成受限工具请求，Java Policy 负责状态转移、错误恢复、人工审批和副作用安全。

第一版只覆盖退款主链路：订单识别、资格校验、补充信息、审批、幂等退款、通知和结果核验。不做换货、补偿、多 Agent、长期记忆或通用工作流平台。

## 技术边界

| 层次 | 负责 | 明确不负责 |
|---|---|---|
| Spring AI 2 | `ChatModel`、`ToolCallback`、`ToolCallingManager`、模型适配、tool schema、Micrometer observation | 不拥有售后状态机，不自动决定重试、审批或终止 |
| LangGraph4j | `StateGraph`、Node/Edge、checkpoint、interrupt/resume、图级流式事件 | 不使用现成 ReAct `AgentExecutor`，不承载业务资格和风险规则 |
| 项目代码 | `AfterSalesAgentState`、Policy Edge、错误分类、修复预算、幂等 Command、审批、Outbox、轨迹评测 | 不重复实现模型 SDK、图执行器或 checkpoint 存储引擎 |
| Java 17（当前） | 承载业务图，并用专用有界执行器隔离阻塞 I/O 节点 | 不替代连接池、限流、超时、bulkhead 或 Reactor |

主链路使用 Spring AI 2 的低层 Tool Calling：`ChatModel` 只返回 tool call，Graph 显式执行 `DECIDE -> VALIDATE -> EXECUTE -> CLASSIFY -> REPAIR/RETRY`。主链路禁用 `ToolCallingAdvisor` 自动循环，避免 Spring AI 和 LangGraph4j 同时拥有控制流。

## 可恢复治理图

```text
INTAKE
-> DECIDE
-> VALIDATE_TOOL
-> QUERY_ORDER
-> EVALUATE_POLICY
-> BUILD_REFUND_COMMAND
-> WAIT_APPROVAL
-> EXECUTE_REFUND
-> VERIFY
-> FINALIZE
```

- 缺少订单号或必要信息时进入 `WAIT_USER_INPUT` interrupt。
- 退款执行前进入 `WAIT_APPROVAL` interrupt，恢复请求必须携带当前 checkpoint ID。
- 参数错误最多修复 2 次；超时、限流和临时不可用最多重试 2 次；状态冲突重新加载 1 次；权限和业务拒绝不重试。
- 相同 input fingerprint 连续失败时提前终止，禁止 Reflection 死循环。
- 退款 Command 使用 `caseId:REFUND` 作为业务幂等键；Case、Command 和 Outbox 在一个 MySQL 事务内提交。

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
| Phase 0 | Java 17 状态图骨架、资格 Policy、错误恢复预算、`MemorySaver`、离线单测 | 已完成轻量验收 |
| Phase 1 | Spring AI 低层 Tool Calling、只读订单工具、结构化错误分类 | 代码与离线测试完成 |
| Phase 2 | MySQL checkpoint、补信息/审批 interrupt-resume、过期 checkpoint 防护 | 已完成；Testcontainers 验证跨实例恢复 |
| Phase 3 | 幂等退款 Command、Outbox、宕机与重复恢复测试 | 已完成；MySQL 事务断言单 Command、单 Outbox |
| Phase 4 | 冻结轨迹集、基线对比、故障注入和完整验收 | 30 条确定性轨迹与 MySQL 恢复完成；真实模型 Full 对比待执行 |
| Phase 5 | Java 21、虚拟线程、并发边界和运行时观测 | 暂缓；当前不作为已落地能力 |

## 下一阶段优先级

1. 使用真实模型执行冻结轨迹，建立任务成功率、工具参数合法率、恢复成功率、模型调用数、Token 成本和 P95 延迟基线。
2. 实现 Outbox 投递器、失败重试和幂等消费，使事务内事件账本形成完整消息闭环。
3. 用订单与退款服务适配器替换 `demo_order` 本地模拟，并补充身份鉴权、跨用户隔离和下游超时边界。
4. 接入 Run/Node/Tool 级观测和故障注入，验证进程中断、多实例恢复、重复审批和并发退款。
5. 以上能力稳定后再评估 Java 21 虚拟线程，以压测结果决定是否升级，不把版本升级本身作为亮点。

## 公共接口与持久化

- `POST /api/v1/after-sales/runs`：启动 Run，输入 `userId/sessionId/message`，返回当前运行状态。
- `POST /api/v1/after-sales/runs/{runId}/resume`：补信息、批准或拒绝，校验 checkpoint ID；过期恢复返回 409。
- `GET /api/v1/after-sales/runs/{runId}`：返回当前节点、等待原因、工具尝试和业务终态。
- `DELETE /api/v1/after-sales/runs/{runId}`：取消非终态 Run。
- `docs/dev-ops/mysql/sql/ai-agent-station.sql` 统一创建运行审计、演示订单、售后 Case、幂等 Command、Outbox 和 LangGraph4j thread/checkpoint 表。
- `sessionId` 只是多个 Run 的归组标识；第一版不保存聊天历史、Turn 或长期记忆。

## 评测与验收

- 冻结 30 条轨迹：12 条正常退款、8 条补信息/规则拒绝、10 条参数错误、超时、状态冲突、宕机恢复和重复 resume。
- 对比 `UNGOVERNED_TOOL_LOOP` 与 `DURABLE_POLICY_GRAPH`，记录任务成功率、首轮参数合法率、错误恢复率、审批遵从率、重复副作用率、恢复一致性、模型调用数和 P95 延迟。
- 硬门槛：未审批退款、跨用户退款和重复退款均为 0；恢复用例结果一致；不可恢复错误不得进入无效重试。
- 默认单测必须离线；MySQL Testcontainers 覆盖 checkpoint、并发审批、事务幂等、Outbox 和故障恢复；真实模型评测单独门控。
- 没有有效 API Key 时不阻塞代码验收，但不得宣称 Agent 效果提升。
