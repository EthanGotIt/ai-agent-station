# Commerce Guardian Agent 架构

## 目标架构与当前实现

长期目标为 Spring AI Agent 负责理解、只读查询和提交事项，确定性 Java Workflow 负责业务核验、补参、授权、命令创建和结果收尾，Worker 独立执行外部动作。一个 Turn 可以在 QuestionCard、Checkpoint 或外部动作等待期间持久化暂停，并在结构化答复或 Command 结果到达后恢复原 Turn 的 Agent 循环；等待期间不占用模型请求或执行线程。Worker 结果先落库，再作为原工具调用的结果交还 Agent，不创建自动续跑 Turn。

`WorkflowRun` 在后续 P8 中只改称 `WorkflowTask`，继续承担当前同一条售后流程的可恢复状态；这项命名不改变实体职责或持久化身份。QuestionCard 答案、Checkpoint 决策、普通 Steer 文本和 Worker 结果是不同类型的输入，分别校验与处理，但都能唤醒原 Turn。普通新消息可 Queue 为下一 Turn，Steer 显式补充当前未结束的 Turn。

截至 2026-09-28，退款、催发货和删除的新 Run 均使用 Java Workflow；Worker 结算不创建自动 Agent continuation。历史 `LEGACY_V1`/`EXPEDITE_GRAPH_V1`/`EXPEDITE_GRAPH_V2` 的标识、快照、Turn/Item 字段和迁移脚本仍保留以便读取与排空，生产运行路径不再装配 LangGraph4j。目标环境的旧 Run 归零需通过 [P5 只读盘点脚本](../scripts/maintenance/workflow-inventory.sql) 提供证据。

## 边界

Core 只表达 `Thread`、`Turn`、`Item`、QuestionCard、ContextSnapshot 和 ExternalActionCommand 的规则与端口；infrastructure 适配 MyBatis-Plus、Spring AI、订单夹具和外部动作；app 只处理配置、HTTP 协议和认证上下文。依赖方向固定为 `app → core`、`app → infrastructure`、`infrastructure → core`。

## 当前实现：Spring AI 协调与 Java Workflow

生产运行时采用 Spring AI + 确定性 Java Workflow：Spring AI 负责模型调用、对话协调和 Tool Calling；Java Workflow 负责固定订单事项的事实读取、资格判断、补参、授权、命令创建和恢复。Core 不依赖框架，`WorkflowRun`、QuestionCard、Workflow Checkpoint 和 `ExternalActionCommand` 是业务事实源。结果结算在本地事务中独立完成，后续用户消息作为普通 Turn 读取最新事实。

不引入 LangChain4j、Embabel 或 Koog。LangChain4j 与 Spring AI 在模型、Tool、Memory 和 RAG 层重叠；Embabel 当前 Java 21 要求且动态 Goal Planning 不符合 JDK 17 与确定性写操作边界；Koog 会引入 Kotlin、协程、序列化及第二套 Agent/Tool/Persistence 运行时。未来若重新评估，必须先有明确的产品边界变化和恢复/持久化契约证据。

历史 LangGraph 的 `runId` 和 `AGENT_GRAPH_SNAPSHOT` 只作为旧数据读取与排空证据保留；运行时代码不再创建、更新或恢复技术图快照。历史编排若在排空后仍被请求，会由 `RetiredAgentWorkflowEngine` 返回 `WORKFLOW_COMPATIBILITY_REQUIRED`，指向兼容版本或明确取消，不按当前 Java 语义猜测恢复。

## Agent-first 分包

Maven 模块负责依赖隔离，Java package 负责能力内聚。能力是第一维度，具体技术只出现在叶子适配器包，不使用按 `model/service/mapper/controller` 横向切开的全局技术层。

```text
core
├── agent.thread          # Thread、Turn、Item 事实和存储契约
├── agent.execution       # FIFO、取消、超时、恢复和 Turn 执行
├── agent.context         # 上下文预算、快照和摘要
├── agent.coordination    # 协调 Agent 输入输出契约
├── agent.workflow        # WorkflowRun、QuestionCard、业务 Checkpoint
├── agent.action          # 外部命令、幂等、Lease 和重试
├── agent.event           # 瞬时运行事件契约
└── commerce.order        # 订单和物流验证夹具

infrastructure
├── agent.*.persistence   # Entity、Mapper 和 Store 适配器
├── agent.coordination.springai
├── agent.action.worker
├── agent.workflow.transaction
└── commerce.order.http / commerce.order.persistence

app
├── bootstrap             # Spring 装配和配置属性
├── agent.api             # HTTP Controller、DTO 和错误协议
└── agent.stream          # SSE 和进程内事件投影
```

新能力先选择所属业务边界，再决定是否需要技术叶子包；禁止空包、泛化 `impl`、`common`、`support` 和职责混杂的横向大包。此规则与 `AGENTS.md` 同步维护，Convention Check 负责阻止旧包结构回流。

## 会话与上下文

`Thread → Turn → Item` 是一套会话及执行记录：Thread 是会话边界，Turn 是一次输入的处理，Item 是该 Thread 内按序追加的事实。`WorkflowRun` 是由某个 Turn 发起并关联回 Thread 的业务事项状态，不是另一套会话或另一份聊天历史。Item 记录可展示的 Workflow 轨迹，WorkflowRun 与 QuestionCard、Checkpoint、Command 保存该事项的可恢复状态和授权、执行约束；状态判断以这些持久化业务事实为准。

登录身份不构成 Agent 实体。一个用户拥有多个 Thread，每个 Thread 保存标题、可选业务上下文和最新 Item 序号：

```text
Thread
├── Turn（一次用户输入的一次执行）
│   ├── USER_MESSAGE
│   ├── TOOL_CALL / TOOL_RESULT
│   ├── WORKFLOW_STARTED / QUESTION_CARD / QUESTION_ANSWER
│   ├── WORKFLOW_CHECKPOINT / WORKFLOW_DECISION
│   ├── ORDER_ACTION_REQUEST
│   ├── WORKFLOW_STEP / AGENT_CONTINUATION / AGENT_DECISION
│   ├── EXTERNAL_ACTION_STATUS
│   └── ASSISTANT_MESSAGE / ERROR
└── ContextSnapshot（截至某个 sequence 的版本化摘要）
```

Item 是对话与执行轨迹的可恢复事实；业务授权、版本和命令状态由对应的 WorkflowRun、QuestionCard、Checkpoint 与 ExternalActionCommand 持久模型维护。每个 Item 的 `PAYLOAD_JSON` 使用 `schemaVersion=1` 和 `kind` 判别 envelope；`TURN_STATE` 记录 QUEUED、ACTIVE、WAITING、终态等生命周期事实，模型最终消息、工具调用/结果、Workflow 状态、订单动作请求和错误均即时持久化。新写入的结构化 payload 由 Infrastructure 的 `JacksonAgentItemPayloadCodec` 统一编码，序号、幂等追加和提交后事件由 `AgentItemJournal` 收口；历史列和旧 envelope 继续可读。Items API 在保留 `payload` 字符串兼容字段的同时提供 envelope 内的结构化 `data`，前端可按 `schemaVersion/kind/data` 消费；模型内部可以流式消费，但 SSE 对外只发送 `ready`、`heartbeat` 和 `item.*`，不再暴露 `assistant.delta` 或瞬时 `turn.*`；客户端断线时先按 `afterSequence` 读取 Items，再订阅事件，不重放丢失的文本增量。

2A-1 生产路径先捕获已提交最大 Sequence，再按每页 300 条读取 `(afterSequence, watermark]`，300 只是分页大小，不限制历史总量。重复、乱序、越界、缺口或未覆盖水位的页面以 `CONTEXT_HISTORY_INVALID` 失败收口；上下文超出输入预算时不把部分历史发送给模型，并以 `CONTEXT_BUDGET_EXCEEDED` 受控失败。2A-2 在此基础上启用 Harness 式模型视图：完整 Prompt 达到总预算 80% 时先在副本裁剪超大 Tool Result，仍有压力才摘要最旧的完整 Turn/Tool 批次，保留最近 16% 和当前请求。原始 Items、Sequence 与 SSE 不删除或改写，摘要只写入可校验的 V2 派生快照；V1 快照忽略并从原始 Items 重建，CAS 冲突采用胜出快照而不重复调用摘要模型。摘要通过无 Tool 的 `ChatModel` 调用，共用 Turn 截止时间和 8,192 token 输出预算；供应商明确上下文溢出时，只有视图已严格缩减才允许一次有限重试。请求前压力处理没有有效缩减但完整 Prompt 仍在硬预算内时继续发送；供应商已拒绝且无法严格缩减，或硬预算不足时，以 `CONTEXT_BUDGET_EXCEEDED` 停止。每次组装记录读取水位、覆盖范围、条数、完整性、当前/峰值估算、固定限长与压力裁剪计数和压缩状态；不记录 Prompt、Thinking 或敏感原文。`WORKFLOW_STEP`、`WORKFLOW_CHECKPOINT`、`WORKFLOW_DECISION` 与 `AGENT_DECISION` 是模型可见的受控事实；`AGENT_CONTINUATION` 只作为运行元数据和前端折叠依据，不直接注入模型文本。

Runtime 的输入边界由 `AgentTurnExecutionRouter` 按 `MESSAGE`、`QUESTION_ANSWER`、`WORKFLOW_DECISION` 和 `ORDER_ACTION` 分派；`AgentTurnInputValidator` 与 `AgentTurnItemPayloads` 负责无副作用的规范化和兼容 payload 构造，新的写入路径通过 `AgentItemJournal` 追加并发布事实。Spring AI 协调器保留模型调用与受控 Tool 生命周期，订单 Tool 的参数解析、字段白名单和输出截断由 `SpringAiOrderToolSupport` 承担；QuestionCard schema 与 Workflow Checkpoint schema 分属各自 Core 模型。历史 `WORKFLOW_ANSWER` Turn 只按消息兼容读取，不进入新 Runtime 路径。这样拆分不改变同 Thread FIFO、持久化 Item、事务边界或外部动作幂等契约。

## 编排、审批与 Turn 恢复

当前运行时由 `ControlledToolCallingManager` 按模型返回顺序执行工具，在成功持久化 QuestionCard 或 Workflow 交互后截断同批工具调用和模型请求。QuestionCard 回答与 Checkpoint 决策仍作为关联的新 Turn 进入 Thread FIFO；Worker 只结算并投影结果，不恢复原 Agent 调用。这是 P8 实施前的实际行为。

P8 目标将工具批次、受控参数、完成结果和恢复位置持久化。QuestionCard 答案、Checkpoint 决策、显式 Steer 文本和 Worker 结果分别走各自的校验协议，但可唤醒同一原 Turn；恢复会补齐原工具结果，不重跑已完成工具或命令。普通消息 Queue 为后续 Turn，Steer 仅在安全处理点修改尚未提交的意图，不能批准或更改已提交命令。

`SpringAiAgentTurnCoordinator` 是唯一协调 Agent，并显式装配一个 `ControlledToolCallingAdvisor` 与 `ControlledToolCallingManager`；Manager 按模型返回顺序执行工具，在 `FINISH`、成功持久化 QuestionCard 或 Workflow 交互后截断同批后续工具和模型请求。只读 Tool 查询订单和物流；订单售后能力统一由 `start_order_service_workflow` 启动确定性 Workflow，不能直接产生外部副作用。协调器使用终态 `FINISH|ASK_USER|START_WORKFLOW`，但入口受工具契约收窄：`complete_agent_cycle` 只接受 `FINISH`，`ASK_USER` 只能由 `request_user_input` 持久化 QuestionCard 产生，`START_WORKFLOW` 只能由 Workflow Tool 产生；固定 Workflow 的人工执行确认由独立 Workflow Checkpoint 承担。模型未形成终态决策时，Runtime 在同一 Turn 内最多发起一次带纠正提示的完整调用，首次自由文本不写入 Item；第二次仍缺失时写入 `AGENT_DECISION_MISSING` 并安全失败，不使用文本假完成。终止 Tool 的受控消息优先于模型追加文本，后续自由文本不会覆盖最终消息。第三轮之后不再创建新的续跑 Turn。Tool Call/Result/Agent Decision 只记录受控参数、状态、截断标志，不记录 Prompt 或 Thinking。Workflow 类型、状态和开放交互使用枚举，并显式执行：

```text
校验 → 持久化 QuestionCard → WAITING_USER_INPUT
     → 用户回答 → 恢复 Agent 或 Workflow
固定写 Workflow → AUTHORIZE → 持久化 Workflow Checkpoint
     → 决策批准 → 本地事务创建 ExternalActionCommand
     → Worker 执行 → SUCCEEDED / MANUAL_RETRY_REQUIRED
```

订单 Workflow 的固定节点图为：

```text
RESOLVE_ORDER → VERIFY_FACTS → SWITCH_REQUIREMENTS → AUTHORIZE
             → EXECUTE_ACTION → VERIFY_OUTCOME → HANDOFF_AGENT（返回 Workflow 结果）
```

以下是已退出的 LangGraph V1/V2 历史试点结构，仅用于解释仍保留的版本、快照及数据迁移；它不是当前准入方式或后续目标。历史试点曾使用双平面流程：

```text
业务事实平面：
Thread → Turn → WorkflowRun → Checkpoint → ExternalActionCommand → Worker
  ↑                                                        ↓
  └────────────── Items / SSE / 最新业务事实 ←─────────────┘

Java Workflow 平面：
RESOLVE_ORDER → VERIFY_FACTS → PREPARE_CONFIRMATION → AUTHORIZE
                                      ↓                 ↓
                              QuestionCard       REVERIFY_FACTS
                                                        ↓
                                      BUILD_ACTION_COMMAND → HANDOFF_WORKER → VERIFY_OUTCOME
```

每次转换都会更新 `STEPS_JSON` 并追加 `WORKFLOW_STEP` Item。外部动作成功后的订单/物流核验发生在本地事务外，命令状态、Workflow 状态和业务结果 Item 在本地事务中一起提交；提交后由 Worker 独立推进，不唤醒模型。授权提交时若订单事实变化，旧 Checkpoint 失效；资格仍允许时重新等待确认，资格不再允许时收口为失败，均不创建外部命令。人工 QuestionCard/Checkpoint 子 Turn 按 Workflow 归属折回来源事项；后台 `RETRY_WAIT` 只等待 Worker。历史 continuation 字段和 Item 只用于兼容读取，不再创建新的 continuation Turn。

QuestionCard 回答使用 `POST /questions/{questionId}/answers`，请求体携带 `clientRequestId + expectedVersion + answers`，按 QuestionCard 的 `resumeTarget=AGENT|WORKFLOW` 恢复并作为同一 Thread 的新 Turn 进入 FIFO。Workflow Checkpoint 决策使用 `POST /workflow-runs/{runId}/checkpoints/{checkpointId}/decisions`，只接受批准或拒绝；批准时重新校验事实指纹，事实变化则标记 `SUPERSEDED` 并回到 `VERIFY_FACTS`。启动、交互创建和版本关闭受本地事务约束；同一 Thread 同时最多一个开放交互。退款仅允许 PAID/SHIPPED/DELIVERED，催发货仅允许 PAID；原始模型思考内容不进入 API、SSE、数据库或日志。

订单卡片使用确定性动作入口 `POST /threads/{threadId}/order-actions`。查询/刷新动作直接调用订单端口并追加结构化 `ORDER_*`/`LOGISTICS_TIMELINE` Item；退款、催发货和直接删除订单记录只启动已有 `ORDER_SERVICE` Workflow，确认前不创建外部动作命令、不调用模型。删除动作通过订单端口的 `DELETE /orders/{id}` 同步清理可删除物流轨迹且不可恢复；隐藏/恢复写入口已移除，旧隐藏字段仅可读兼容。动作请求同时保存在 Turn 的 `INPUT_KIND=ORDER_ACTION`、`ORDER_ACTION_JSON` 和 `ORDER_ACTION_REQUEST` Item 中，按 `clientRequestId` 幂等并校验来源 Turn、订单归属和 Thread FIFO。Workflow 回答子 Turn 通过 `sourceTurnId`/`runId` 折回来源 Turn，技术 Turn 仅在 Item 检查器中展开。

## Runtime 可靠性

队列键是 Thread：同一 Thread 严格 FIFO、任意时刻一个 ACTIVE Turn；不同 Thread 可并行。Turn 持久状态通过 `VERSION_NO` 条件更新执行 CAS，版本竞争或终态重写会丢弃后续事实写入。排队 Turn 可直接取消，ACTIVE Turn 通过运行上下文协作取消；已提交的外部副作用不会回滚。只有需要模型的路径组装上下文，订单卡片动作、Workflow 问答恢复、审批和问题取消等确定性路径跳过历史预算检查。每次模型请求都检查完整上下文并预留输出额度，完成后按请求标识只结算一次 usage；usage 缺失、零值、断流或取消均保守扣除整笔预留，输出结算同时进入低基数观测。默认应用上下文总预算为 65,536 估算 token，输入预算扣除 1,500 预留，单次模型输出上限为 1,024，Turn 累计生成额度为 8,192，截止时间为 4 分钟。相同工具、规范化参数和稳定错误码连续失败 3 次后以 `FALLBACK` 结束并写入 `TOOL_REPEATED_FAILURE`；成功或不同失败组合会重置计数。资源停止使用 `STOP_LIMIT`，代码为 `CONTEXT_BUDGET_EXCEEDED` 或 `OUTPUT_BUDGET_EXCEEDED`，历史读取异常使用独立的 `CONTEXT_HISTORY_INVALID`，前端不把这些终态投影为业务成功。没有摘要期间长历史可能受控停止。队列等待、Turn、工具、外部动作和 SSE 流/心跳均可独立配置。

外部命令以 `(userId, idempotencyKey)` 唯一。Worker 先在本地事务中原子 Claim Lease，再在事务外调用远程系统；PENDING、RETRY_WAIT 和过期 PROCESSING 都可被领取。仅瞬时错误按指数退避，永久错误直接进入人工重试；动作超时也会形成可分类结果。订单 HTTP 适配器必须把命令的 `idempotencyKey` 作为 `Idempotency-Key` 请求头传给退款、催发货和删除接口，由订单服务按该键去重；本地演示执行器则在同一本地事务中完成订单状态 CAS、订单/物流删除和幂等回执写入。人工重试沿用原命令和幂等键，重复回答、重复 Claim 和重启恢复不会产生第二次业务写入。

## HTTP 和事件

唯一 API 前缀为 `/api/agent`：

```text
POST   /threads
GET    /threads?page=0&size=20
GET    /threads/{threadId}
PATCH  /threads/{threadId}
GET    /threads/{threadId}/items?afterSequence=0&limit=200
POST   /threads/{threadId}/turns
POST   /threads/{threadId}/order-actions
POST   /turns/{turnId}/cancel
GET    /threads/{threadId}/events
GET    /turns/{turnId}/execution
GET    /threads/{threadId}/interaction
POST   /questions/{questionId}/answers
POST   /workflow-runs/{runId}/checkpoints/{checkpointId}/decisions
POST   /workflow-runs/{runId}/retry
```

SSE 事件包含完整 envelope：`eventId、threadId、turnId、itemId（可选）、type、sequence、timestamp、payload`；公开类型收敛为 `ready`、`heartbeat` 和 `item.*`。SSE 当前仍以兼容的 `payload` 字符串承载 Item，结构化 `data` 由 Items API 提供；客户端按真实 `eventId/itemId/sequence` 去重。身份只从认证上下文读取，不信任请求体中的用户字段。

执行回放接口从同一组 Item 事实投影当前 Turn 的队列、上下文、Tool、Workflow、审批和外部动作时间线；回放过程不调用模型、不启动 Workflow，也不重放外部副作用。运行指标只保留低基数维度：队列等待、Turn/Tool 耗时、上下文预算、Workflow 等待、Worker 重试、Lease 接管和失败分类。`scripts.runtime_eval` 使用 Fake 协调器和 Fake 执行器做确定性门禁，Live Model 评测单独产出质量报告。

## 数据库

`docs/dev-ops/mysql/commerce-guardian-agent.sql` 是新库的破坏性基线；已有库必须先备份并由 `db/migration/V1__align_workflow_question_recovery.sql` 至 `V7__persist_agent_continuations.sql`、`V8__persist_langgraph_snapshots.sql`、`V9__split_question_cards_and_workflow_checkpoints.sql`、`V10__persist_context_compaction_metadata.sql`、`V11__persist_workflow_orchestration_version.sql`、`V12__persist_langgraph_orchestration_version.sql` 逐版本增量升级。V8 只增加可重建的 `AGENT_GRAPH_SNAPSHOT` 技术表，V9 将提问与执行确认拆为独立事实，V10 为上下文 V2 快照增加格式、来源范围、估算和摘要版本元数据，V11 为每个 `AGENT_WORKFLOW_RUN` 写入不可变的编排版本，V12 为图快照写入同样的不可变编排版本，并将历史记录归入 `LEGACY_V1`；历史业务事实和已有 Run 状态不被重写。旧 `AGENT_WORKFLOW_QUESTION`、Turn 中的旧回答列和旧 `WORKFLOW_ANSWER` 标记在保留期内只读，仅供迁移/历史投影使用，运行时代码不再映射或写入。基线保留这些历史列/表以支持迁移演练，同时创建当前 `AGENT_QUESTION_CARD`、`AGENT_WORKFLOW_CHECKPOINT`、`AGENT_GRAPH_SNAPSHOT`、`EXTERNAL_ACTION_COMMAND` 和 `EXTERNAL_ACTION_RESULT`。同一用户的同一来源 Turn 和 Workflow 类型只能有一个 WorkflowRun；迁移不得重建或覆盖已有业务事实。

> 以下 2B-1/V2 记录描述历史试点和数据兼容边界，不代表当前生产执行路径。P5 退出后新 Run 和结果结算均走 Java Workflow。

2B-1/V2 历史试点曾由 `AI_AGENT_EXPEDITE_GRAPH_MODE=OFF|V1|V2` 控制，并使用图快照恢复。现有数据库中的这些版本、快照和 Item 仍可读取；新配置不再打开旧图执行。旧非终态 Run 必须在兼容版本完成或由用户明确取消，排空后由 `RetiredAgentWorkflowEngine` 受控拒绝。

Java 新 Run 由 `AI_AGENT_EXPEDITE_MODE=JAVA`、`AI_AGENT_REFUND_MODE=JAVA` 和 `AI_AGENT_DELETE_MODE=JAVA` 路由到对应编排版本，默认开启；开关只影响新 Run，恢复按持久化版本选择 Java 或退休兼容边界。Java 引擎以 `WorkflowRun.stateJson/stepsJson`、QuestionCard、Checkpoint 和 ExternalActionCommand 恢复，不创建或读取图快照；步骤依次为 `RESOLVE_ORDER`、`VERIFY_FACTS`、`PREPARE_CONFIRMATION`、`AUTHORIZE`、`REVERIFY_FACTS`、`BUILD_ACTION_COMMAND`、`HANDOFF_WORKER`、`VERIFY_OUTCOME`。订单缺失或有歧义时通过 QuestionCard 补参，授权继续由 Checkpoint 表达。批准前在事务外重读订单事实，事务内锁定并校验 Run/Checkpoint 版本后创建唯一命令。三类 Java Run 的 Worker 结果持久化不创建 Agent continuation。

历史快照恢复证据保留在实施追踪中，但运行时不再写入或读取图快照。未知或遗漏的旧编排版本不得回退 Java 语义，而是以 `WORKFLOW_COMPATIBILITY_REQUIRED` 失败并指向兼容版本；历史表和迁移文件保留，支持数据读取、排空和回滚。

V6—V13 现场迁移先备份配置库并在一次性克隆库执行；确认 `INPUT_KIND` 非空、`ORDER_ACTION_JSON` 和 `CONTINUATION_JSON` 可空，历史 Workflow 状态不被重写。`CONTINUATION_JSON` 和 `AGENT_GRAPH_SNAPSHOT` 继续保留以支持历史读取，但新 Worker 结算不创建 continuation。P5 目标库排空前必须执行只读盘点并确认旧服务、Worker 和队列已退出。

本地现场复核可使用 `docs/review-runbook.md` 和 `scripts/review/review-services.ps1`。订单夹具通过 `ORDER_SERVICE_FIXTURE_EXPEDITE_TRANSIENT_FAILURES` 注入有限的催发货可重试失败，并在 `/_fixture/stats` 暴露注入次数；注入只持久化验收故障计数，不写入订单服务幂等记录或业务状态。

## Week 4 演示验收边界（2026-09）

`scripts.acceptance` 是本机验收工具，不是生产流量回放器。它先通过 `/api/agent` 验证 Thread → Turn → Item 的恢复、游标、开放交互、Turn 幂等和执行轨迹回放，再可选连接独立 SQLite 订单夹具验证物流、退款、催发货重试和显式授权的删除。夹具动作通过唯一 `Idempotency-Key` 重放并对照 `/_fixture/stats`；删除开关默认关闭，只允许在操作者确认数据库可丢弃后作用于固定演示订单。该工具不保存模型原文、Thinking、密钥或完整订单响应，也不改变 HTTP、SSE 或 Item 事实协议；V2 ContextSnapshot 和 Workflow 编排版本都是可校验的派生/路由元数据。

Week 4 的真实模型质量报告、数据库副本迁移和浏览器矩阵属于外部验收证据，不能由确定性 runner 或前端组件测试推断完成；本轮已在真实 Agent + 独立订单夹具上完成 HTTP Thread/Turn/Item、开放交互、幂等、执行回放、物流、退款、催发货重试和完整催发货黄金路径，并以合成订单完成真实 DeepSeek 查询、Worker 结果核验、模型续接总结和刷新恢复。四尺寸页面、移动抽屉/Escape、控制台无错误及深浅主题/reduced-motion smoke 已记录；`1536×730` 另完成离线/在线后的带游标 SSE 重订阅和 QuestionCard 错误焦点，其他尺寸专项证据仍按运行手册逐项补录。

## 阶段七验收状态（更新至 2026-09-13）

验证结果按执行日期与范围记录在[实施追踪](implementation-traceability.md)和[现场复核](review-runbook.md)，当前待办见[任务交接](../.codex/task-handoff.md)。本架构文档描述设计与实现边界，不作为最新测试结果或生产验收通过的证明。
