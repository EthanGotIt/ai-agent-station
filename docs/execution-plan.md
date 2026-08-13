# AI Agent Station 执行计划与验收矩阵

## 当前能力闭环

| 能力 | 已实现 | 自动验证 | 真实验证 |
|---|---|---|---|
| Session FIFO、取消、SSE | 同一 `userId + sessionId` 串行，排队与执行请求均可取消 | Core 队列测试 | 排队取消、执行取消与后续请求放行通过 |
| Order Workflow V2 | `QUERY`、`TRACK`、`DIAGNOSE`，近期订单选择、问题补充、实时归属与物流诊断 | `OrderInquiryWorkflowTest`、评测场景 | QuestionCard、回答与重启恢复通过 |
| After-sales Workflow V2 | 订单/原因/说明/确认卡，自动退款、人工审核、拒绝与状态查询 | `AfterSalesRefundWorkflowTest`、评测场景 | 自动退款、人工审核、资格拒绝、幂等与状态查询通过 |
| 售后事实 | `DEMO_AFTER_SALES_CASE` 与 `DEMO_REFUND_COMMAND` 分离，订单申请唯一、退款命令按运行幂等 | Local Gateway 测试 | 非生产 MySQL 初始化、重置与数据边界通过 |
| 退款生命周期 V2.1 | 审核批准/驳回、持久化异步命令、HTTP 渠道幂等、租约领取、有限重试、最终失败与人工再试 | Core 生命周期、HTTP Executor、Controller、Worker 与验收脚本测试 | 本机独立 MySQL + HTTP 模拟渠道六场景通过，模型调用为 0 |
| Router Policy | Core 规则优先；classpath Policy 仅声明 executor/domain/operation 白名单与冲突优先级 | 路由业务语料、Qwen Mock HTTP 与资源契约测试 | `qwen3.7-plus` 真实兜底路由通过 |
| ReAct 工具体系 | 五个只读 `ALLOW` 工具；会话偏好写入固定为 `ASK`；百炼原生工具禁用 | AgentScope 工具测试 | 五读一写真实串联通过 |
| ReAct AgentSkill | 唯一、只读的 `agent-station-business-orchestration` 指导 Tool 选择和无代码只读编排；Tool 不分组隐藏 | Skill Repository 与 ReAct 装配测试 | 五类场景各 5/5 命中预期路由和 Tool 有序子序列 |
| HITL | ASK 走同一 SSE 的 `intervention` 与旁路确认；ReAct 无恢复，Workflow 持久化恢复 | 确认协议与状态清理测试 | 真实确认、拒绝、取消与超时通过，未发生旁路写入 |
| 会话记忆 | 生成/使用分离、tombstone、人工优先、版本契约、提示注入边界 | `AgentMemoryServiceTest`、Controller 测试 | 写入、下一轮偏好生效及 CRUD 串联通过 |
| 控制台/评测 | React SSE 控制台、业务卡片、记忆管理；规则 HTTP 回归 Markdown 报告 | Vitest、`scripts.evaluation` | SSE 时间线、QuestionCard、ASK、业务卡片与记忆管理冒烟通过 |

## 关键边界

- 退款、支付、发货、删除和账户变更不进入 ReAct；退款仅在最终 Workflow QuestionCard 确认后执行。
- `save_session_preference` 是可逆的人工会话记忆写入，拒绝、超时与取消均不执行；其结果可经记忆 API 编辑或软删除。
- ReAct 使用 `InMemoryAgentStateStore`，不支持跨请求/跨重启恢复；QuestionCard 以 MySQL `WORKFLOW_RUN` 保存，必须可恢复。
- 百炼模型原生工具（含原生联网搜索）保持关闭。未来需联网或接入第三方能力时，只通过框架 MCP 组件装配并独立验收。
- Router 不读取记忆。订单、物流、退款业务事实永远重新查询；Workflow 建议值必须经 answers API 显式提交。
- Router Policy 与 AgentSkill 职责分离：前者指导受控路由，后者指导 ReAct Tool 顺序、停止和降级；二者均不能替代 Core、Tool 权限或 Workflow。
- 本轮不引入向量库、跨会话记忆、Redis、分布式 ReAct 协调、Flyway 或 Testcontainers。

## V2.1 退款业务生命周期闭环

- 操作员 API 以 `X-Operator-Id` 记录操作来源；该 Header 仅适用于已由可信网关认证的内部调用，不能代替生产认证授权。
- 申请单使用 `PENDING_REVIEW`、`REFUND_PROCESSING`、`REFUND_FAILED`、`COMPLETED`、`REJECTED` 表达面向用户的状态；退款命令使用 `PENDING`、`PROCESSING`、`RETRY_WAIT`、`COMPLETED`、`FAILED` 表达后台执行状态。
- 审核决策和人工重试都使用客户端生成的幂等 ID 与 `expectedVersion`；命令按申请单唯一，自动退款与批准审核的申请单/命令创建同事务提交。
- 本地 Worker 的领取、执行、结果收敛分离：领取和收敛各自有短事务，渠道调用不持有数据库事务；租约可恢复进程中断，自动重试受配置上限约束。
- React 控制台提供筛选队列、详情、批准、需说明的驳回及最终失败重试；状态查询 Tool 同步返回命令状态、尝试次数和失败码。

## V2.1 验收结论

- 2026-08-12 在 `127.0.0.1/AI_AGENT_STATION` 与随机本机端口 HTTP 模拟渠道完成六场景验收，数据库重置完成，外部模型调用为 0。
- 自动成功、重复人工审核、两次失败后成功、最终失败后人工幂等重试、渠道已生效时的进程重启租约恢复、操作员 API 与 Workflow 状态一致性全部通过。
- 所有渠道重试复用原 `refundId`；每个场景唯一退款效果均为 1。脱敏结论见 [V2.1 退款验收报告](acceptance/v21-refund-20260812.md)，完整本地产物保留在 `target/refund-acceptance/`。

## V2 验收结论

- 2026-08-12 使用 `qwen3.7-plus`、现有 Thinking 配置和独立非生产 MySQL 完成真实验收，45/45 场景通过，数据库重置完成。
- `--skill-stability-runs 5` 的近期订单比较、单订单复盘、订单加物流、售后政策、会话偏好五类场景均为 5/5。
- 修复验收发现的问题后，相关回归、完整真实验收及控制台人工串联均通过；V2 标记为已验收。
- 脱敏结论固化在 [V2 验收报告](acceptance/v2-20260812.md)。完整运行产物保留在本地 `target/live-acceptance/`，不作为源码发布物。

后续只在 V2.1 中评审新能力；是否对照其他模型、进行并发压测、故障注入或上线准备不属于本次结论。

当前交付不包含上线和现场演示，因此 Docker、Docker Compose、Nginx、TLS 证书和公网访问均不属于必需环境。
