# Agent 决策契约

## 目标

Commerce Guardian Agent 的每个普通消息 Turn 都必须以可验证的受控决策收口。模型自由文本只能作为协调过程中的暂态内容，不能被当作“已完成”事实写入持久化 Item。

## 终止边界

- `complete_agent_cycle` 只接受 `FINISH`，返回消息由 Tool 参数提供并作为最终用户消息。
- `ASK_USER` 只能由 `request_user_input` 创建并持久化 Agent `QuestionCard` 后产生；问题卡不是执行授权。
- `START_WORKFLOW` 只能由 `start_order_service_workflow` 产生，并且必须伴随 `WorkflowRun` 以及 QuestionCard 或 Workflow Checkpoint。
- 退款、催发货、删除等外部写操作仍由确定性 Workflow 和 `ExternalActionCommand` 承担，模型不直接写外部系统。

## 缺失决策处理

Runtime 首次收到没有决策且没有结构化副作用的协调结果时，在同一 Turn 内发起一次纠正调用。纠正调用要求模型使用终止 Tool、QuestionCard 或 Workflow Tool 收口；只读查询可按需重试，已经创建的 Workflow/QuestionCard 不会再次创建。

若纠正调用仍没有可验证决策，Runtime 追加稳定错误码 `AGENT_DECISION_MISSING`，将 Turn 置为 `FAILED`，不写入自由文本，也不留下新的等待交互。`MODEL_TEXT_FALLBACK` 不再存在。

## 前端投影

前端只从 Item 历史投影该错误。只有 `errorCode=AGENT_DECISION_MISSING` 的 Turn 显示“再次尝试”；点击后调用现有提交 Turn API，以原用户请求创建新的 Turn 和新的 `clientRequestId`。页面不自动重放，不复用旧请求 ID，也不把普通错误转换为 Agent 重试。

`AGENT_DECISION` 可选记录 `correctionAttempt=true`，用于运行详情审计；该字段不改变 HTTP/SSE envelope、Thread→Turn→Item 模型或 V9 数据库结构。

## 验证证据

- Core：纠正成功、二次缺失安全失败、带结构化副作用的非法 Workflow 决策不重复调用。
- Infrastructure：终止 Tool 只接受 `FINISH`，受控终止消息覆盖追加自由文本，重复 Workflow/QuestionCard Tool 不产生第二个事实。
- Frontend：错误码投影、无开放交互、仅针对该错误创建新 Turn/request ID。
