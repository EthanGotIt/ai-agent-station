# Durable After-Sales Agent 架构

## 定位与范围

这是一个可恢复的售后退款流程，不是通用 Agent 平台。第一版覆盖订单识别、信息收集、资格校验、补充信息、人工审批、幂等退款、通知和结果核验；不覆盖换货、补偿、多 Agent、长期业务记忆或生产支付接入。

模型的权限被限制在“下一步需要哪些信息、调用哪个已声明的只读证据工具”。退款资格、状态转换、审批、幂等键和副作用执行都由 Java 控制。

## 责任边界

| 层次 | 负责 | 不负责 |
|---|---|---|
| Spring AI | `RefundPlanningAgent` 的 JSON Plan、Case 级 `SessionMemoryAdvisor`、`TodoWriteTool` 检查清单 | 退款决策、工具自动循环、业务事实存储 |
| Spring State Machine | 四个业务状态、事件、Guard、Action、按 `ssm_state` 恢复 | 资格规则、退款副作用、模型调用重试策略 |
| Domain Policy | Plan schema 和能力校验、RePlan 预算、退款资格、授权与幂等边界 | 模型自由推理或外部 HTTP 细节 |
| Infrastructure | 只读 commerce 证据、MyBatis 持久化、checkpoint/Turn 边界、Outbox/Inbox | 跨服务业务编排 |

## 受控执行流

```text
INTAKE
  -> RefundPlanningAgent produces a JSON Plan
  -> Policy validates schema, evidence gaps, action, tool and input
  -> Gatherer executes only the first approved step
     -> ASK_USER: persist interrupt and wait for SUPPLY_INFO
     -> TOOL_CALL: persist process snapshot and RePlan
  -> eligible: PENDING_APPROVAL interrupt

PENDING_APPROVAL
  -> APPROVE: idempotent refund command, then COMPLETED
  -> REJECT or ineligible: REJECTED
```

每个 Turn 的过程快照可以帮助诊断，但只有 Turn 完成后提交的边界 checkpoint 会写入 `after_sales_case.checkpoint_id`。恢复时新建状态机实例，从 checkpoint 的 `ssm_state` 和扩展状态直接恢复；不会从 `INTAKE` 重放事件来模拟旧状态。

## 事实来源与恢复

- `sessionId`：调用方归组字段，不作为模型记忆键。
- `caseId`：售后业务流程、模型记忆隔离键与状态机 thread key。
- `turnId`：一次 start、resume 或重试尝试。
- `agent_checkpoint`：Plan、工具和 Policy 的过程快照，以及可恢复边界。
- `after_sales_case.checkpoint_id`：Case 当前唯一可恢复的位置。

恢复锁带过期时间。进程异常后，其他实例可接管过期锁，并从上一个已提交 Turn 边界继续。只读工具允许重放；退款 Command 使用稳定的 `caseId:REFUND` 幂等键，避免重复副作用。

Session Memory 只保存规划对话。数据库和 checkpoint 才是业务状态、审批和退款结果的事实来源；记忆组件异常时，确定性 Plan 仍可维持安全主链。

## 只读证据能力

`IAfterSalesToolPort` 接收服务端 `caseId`、`userId`、`turnId` 上下文，并只执行已通过 Policy 的步骤。默认仅启用 `query_order`；HTTP commerce 契约准备好后可显式启用 `query_logistics` 与 `query_refund_history`。本地适配器不伪造物流或退款历史。

模型输出非法 Plan 时会降级为确定性 Plan。最多允许 3 次 RePlan，每一步最多 2 次重试；退款始终保留在人工审批之后。

## 生产联调方向

1. 对接真实订单、物流、退款测试环境，并以网关认证替代开发请求头。
2. 将本地 Outbox 消费者替换为生产 MQ，保留 Outbox/Inbox 语义。
3. 接入 Micrometer 后端并观察 Plan、工具、RePlan、checkpoint、恢复冲突与退款指标。
4. 使用真实数据库、HTTP 依赖和连接池做容量与故障演练。

运行方式、HTTP 接口和验收命令见 [after-sales-agent.md](after-sales-agent.md)。
