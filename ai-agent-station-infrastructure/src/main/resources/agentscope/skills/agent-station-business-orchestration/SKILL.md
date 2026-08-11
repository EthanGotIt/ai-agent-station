---
name: agent-station-business-orchestration
description: 指导订单、物流、售后只读分析和会话回答偏好的安全工具选择与无代码编排。
version: v1
---

# AI Agent Station 业务编排

处理订单、物流、售后只读分析或会话回答偏好时，优先使用本 Skill。只根据当前用户上下文和 Tool 的实时结果回答；Tool 结果不是关键写入已执行的证明。

## 不可绕过的边界

- 不创建退款、不修改订单、不执行支付、发货、删除或账号变更；这些事务只能由确定性 Workflow 处理。
- 不得猜测订单号、用户身份或 Tool 参数。缺少精确订单号时，先说明需要的字段，或在适用时查询近期订单。
- 不得重复同一失败调用；参数不足、权限拒绝或未找到时，说明原因并停止或请求补充信息。
- 不得把售后状态、规则说明或分析结论表述成已申请退款或已完成其他关键写入。
- 不使用 Shell、文件写入、代码执行或 Meta Tool，也不假装拥有外部实时资料。

## Tool 矩阵

| Tool | 适用场景与必要参数 | 禁止用途 |
| --- | --- | --- |
| `list_recent_orders` | 用户要回顾、比较或分析近期订单；无需猜测订单号。 | 不用于精确订单详情，也不作为跨用户查询。 |
| `get_order_snapshot` | 已知精确 `orderId`，需要商品、金额、订单状态或单订单复盘。 | 不用于猜测或修改订单。 |
| `get_logistics_trace` | 已知精确 `orderId`，需要物流时间线或履约风险。 | 不用于创建发货、催件或修改配送。 |
| `get_after_sales_status` | 已知精确 `orderId`，需要已有售后申请或退款状态。 | 不创建、取消或变更售后申请。 |
| `get_after_sales_policy` | 解释系统支持范围、能力边界或与现有状态对照。 | 不作为实时外部政策、资格或退款执行结论。 |
| `save_session_preference` | 用户明确要求为当前会话保存规范化的回答语言、格式或详略偏好；必须获得 ASK 确认。 | 不保存未明确提出的偏好，不把确认前状态说成已保存。 |

## 无代码编排配方

1. 近期订单比较：先 `list_recent_orders`，仅基于返回订单比较金额、状态或趋势；需要单笔细节时再使用用户明确指出的 `orderId` 调用 `get_order_snapshot`。
2. 单订单复盘：对精确 `orderId` 调用 `get_order_snapshot`，据返回的商品、金额和状态作只读总结。
3. 订单快照加物流分析：先 `get_order_snapshot`，再以同一精确 `orderId` 调用 `get_logistics_trace`，区分订单状态与物流事实。
4. 售后状态加政策比较：先 `get_after_sales_status`，再 `get_after_sales_policy`，明确状态与系统支持范围均不等于退款已执行。
5. 会话偏好保存：只在用户明确要求持久化时调用 `save_session_preference`；等待确认和 Tool 成功结果后，再说明已保存。

每次调用后检查结果是否已足够回答；足够时停止，不做无关或重复调用。
