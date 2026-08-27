# Commerce Guardian Agent

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

主要用户是开发者和技术评审者。他们以普通售后用户的身份操作真实订单查询、物流诊断和订单动作，同时需要在出现错误、等待确认或外部动作重试时查看可恢复的运行事实。

## Product Purpose

Commerce Guardian Agent 将订单售后请求转换为可恢复的 Thread → Turn → Item 执行记录。只读查询直接返回结构化业务事实；退款、催发货、隐藏和恢复订单记录通过持久化 QuestionCard、确定性 Workflow 与幂等外部动作完成。成功标准是业务结果清楚、授权边界明确、刷新或断线后仍能恢复事实和状态。

## Positioning

产品的差异机制是把 Agent 对话、确定性 Workflow、人机确认和可靠外部动作放进同一条可恢复事实链，而不是把模型文字当作唯一结果。

## Operating Context

使用者在本地开发环境通过 React 工作台操作演示订单服务或独立 HTTP 订单服务。Thread 是上下文根，Turn 是一次请求或 Workflow 回答，Item 是按 Thread 单调序列保存的事实；SSE 只负责实时体验和断线续传，最终事实来自 Items。

## Capabilities and Constraints

- 支持订单搜索、订单详情、物流时间线、退款、催发货、订单历史隐藏与恢复。
- 外部写操作必须经过确定性 Workflow 的 `AUTHORIZE` Checkpoint，不能由模型直接产生副作用；缺少订单号或退款原因时才使用 QuestionCard 提问。
- 每个 Thread 最多一个开放交互；QuestionCard 只收集受控字段，Workflow Checkpoint 只确认动作、对象、影响和事实版本，拒绝/取消不创建外部命令。
- Workflow 节点、Agent 决策和续跑触发事实均以受控 Item 持久化；续跑失败不得改写已成功的订单事实。
- 同一 Thread 串行处理，QuestionCard、WorkflowRun、Checkpoint 和 ExternalActionCommand 必须持久化。
- 不展示原始 Thinking；业务结果优先使用结构化 Item，运行细节按需查看。
- 本阶段不新增退货、换货或多订单批处理等业务种类。

## Brand Commitments

- 对外产品名统一使用 Commerce Guardian Agent。
- 界面服务开发者操作，但默认语言从用户可理解的订单和售后事实出发。
- 视觉隐喻是订单调度清单：路线、回执、序列和状态必须服务于定位问题，不做装饰性监控大屏。

## Evidence on Hand

- 仓库已有可运行的 React + TypeScript + Vite 工作台：`agent-fronted`。
- 仓库已有 Core、Infrastructure、App 三层 Java 服务，以及真实浏览器、MySQL、独立 HTTP 订单服务和 DeepSeek 验收记录。
- 真实业务事实来自本地夹具或独立 HTTP 订单服务；不得编造生产平台、客户或业务指标。

## Product Principles

1. 业务结果先于工程轨迹。
2. 每一个关键动作都有明确授权和可核验回执。
3. Item 是恢复和解释执行的事实来源。
4. 失败可见、可恢复，且不重复产生外部副作用。

## Accessibility & Inclusion

桌面、窄屏和移动 Web 均可完成查询与确认；所有操作具备键盘焦点、Esc 关闭、可读状态文本和 reduced-motion 降级。颜色不是状态的唯一表达方式。
