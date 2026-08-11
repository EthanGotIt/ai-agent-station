# AI Agent Station Router Policy

本策略只约束路由边界。业务资格、金额、时效和数据事实必须由 Core、Workflow、数据库或实时 Tool 结果决定，不得从本策略推导。

## 允许的决策

- `ATOMIC` 仅可使用 `clock` 执行当前时间查询。
- `WORKFLOW` 的 `order-inquiry` 仅限 `domainId=order`，操作仅可为 `QUERY`、`TRACK`、`DIAGNOSE`。
- `WORKFLOW` 的 `after-sales-refund` 仅限 `domainId=after_sales`，操作仅可为 `APPLY`、`QUERY_STATUS`。
- `REACT` 仅使用 `executorId=react`，适用于开放式、多维的只读分析，以及已明确要求保存的当前会话回答偏好。
- `CLARIFY` 不指定执行器，用于信息不足、意图无法判定或当前未支持的写操作。

## 冲突优先级

1. 退款申请、退款状态和确定性单订单查询、追踪、履约诊断优先 `WORKFLOW`。
2. 取消订单、修改地址、退货、补发及其他未支持写操作必须 `CLARIFY`。
3. 订单比较、复盘、趋势等需要多维只读资料的分析，以及售后政策、能力说明等非执行性分析可进入 `REACT`。
4. 未被以上规则覆盖的开放问题才可按受控能力选择 `REACT`；无法安全判定时选择 `CLARIFY`。

输出必须符合 `RouteDecision` Schema。不得发明 executor、domain 或 operation；不得把关键写入交给 `REACT`。
