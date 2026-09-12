# Commerce Guardian Agent 架构

## 边界

Core 只表达 `Thread`、`Turn`、`Item`、QuestionCard、ContextSnapshot 和 ExternalActionCommand 的规则与端口；infrastructure 适配 MyBatis-Plus、Spring AI、订单夹具和外部动作；app 只处理配置、HTTP 协议和认证上下文。依赖方向固定为 `app → core`、`app → infrastructure`、`infrastructure → core`。

## V7 Workflow 框架决策（2026-08）

生产运行时采用 Spring AI + LangGraph4j 的窄边界混合架构：Spring AI 只负责模型调用、对话协调和 Tool Calling；LangGraph4j 1.8.20 负责固定订单 Workflow 的节点、条件边和中断，节点推进的业务事实仍由 Core 规则和本地持久化端口提交。Core 不依赖任何一个框架，业务 `WorkflowRun`、QuestionCard、Workflow Checkpoint 和 `ExternalActionCommand` 仍是唯一事实源。

不引入 LangChain4j、Embabel 或 Koog。LangChain4j 与 Spring AI 在模型、Tool、Memory 和 RAG 层重叠；Embabel 当前 Java 21 要求且动态 Goal Planning 不符合 JDK 17 与确定性写操作边界；Koog 会引入 Kotlin、协程、序列化及第二套 Agent/Tool/Persistence 运行时。重新评估条件仅限于：JDK 升级到 21、确定性 Workflow 边界被产品明确放弃，或 Spring AI/LangGraph4j 无法满足已验收的恢复与持久化契约；在此之前不得并行引入第二套 Agent 运行时。

LangGraph 的 `runId` 是技术 graph thread ID。项目内 `MybatisLangGraphCheckpointSaver` 只保存节点、下一节点、序列化状态、业务 WorkflowRun 版本和事实指纹到 `AGENT_GRAPH_SNAPSHOT`；不保存或决定业务授权。2B-1 的明确订单催发货试点使用按 Run 隔离的进程内图状态，恢复先读取业务事实并在缺少技术状态时重建；2B-2 才接入生产图快照校验和跨进程恢复。LangGraph4j 内置 AgentExecutor 和内置 MySQL Saver 不进入生产依赖路径，避免第二套业务事实表和 Jackson 2 序列化链。

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

Item 是唯一事实来源。每个 Item 的 `PAYLOAD_JSON` 使用 `schemaVersion=1` 和 `kind` 判别 envelope；`TURN_STATE` 记录 QUEUED、ACTIVE、WAITING、终态等生命周期事实，模型最终消息、工具调用/结果、Workflow 状态、订单动作请求和错误均即时持久化。新写入的结构化 payload 由 Infrastructure 的 `JacksonAgentItemPayloadCodec` 统一编码，序号、幂等追加和提交后事件由 `AgentItemJournal` 收口；历史列和旧 envelope 继续可读。Items API 在保留 `payload` 字符串兼容字段的同时提供 envelope 内的结构化 `data`，前端可按 `schemaVersion/kind/data` 消费；模型内部可以流式消费，但 SSE 对外只发送 `ready`、`heartbeat` 和 `item.*`，不再暴露 `assistant.delta` 或瞬时 `turn.*`；客户端断线时先按 `afterSequence` 读取 Items，再订阅事件，不重放丢失的文本增量。

2A-1 生产路径先捕获已提交最大 Sequence，再按每页 300 条读取 `(afterSequence, watermark]`，300 只是分页大小，不限制历史总量。重复、乱序、越界、缺口或未覆盖水位的页面以 `CONTEXT_HISTORY_INVALID` 失败收口；上下文超出输入预算时不把部分历史发送给模型，并以 `CONTEXT_BUDGET_EXCEEDED` 受控失败。2A-2 在此基础上启用 Harness 式模型视图：完整 Prompt 达到总预算 80% 时先在副本裁剪超大 Tool Result，仍有压力才摘要最旧的完整 Turn/Tool 批次，保留最近 16% 和当前请求。原始 Items、Sequence 与 SSE 不删除或改写，摘要只写入可校验的 V2 派生快照；V1 快照忽略并从原始 Items 重建，CAS 冲突采用胜出快照而不重复调用摘要模型。摘要通过无 Tool 的 `ChatModel` 调用，共用 Turn 截止时间和 8,192 token 输出预算；供应商明确上下文溢出时，只有视图已严格缩减才允许一次有限重试。请求前压力处理没有有效缩减但完整 Prompt 仍在硬预算内时继续发送；供应商已拒绝且无法严格缩减，或硬预算不足时，以 `CONTEXT_BUDGET_EXCEEDED` 停止。每次组装记录读取水位、覆盖范围、条数、完整性、当前/峰值估算、固定限长与压力裁剪计数和压缩状态；不记录 Prompt、Thinking 或敏感原文。`WORKFLOW_STEP`、`WORKFLOW_CHECKPOINT`、`WORKFLOW_DECISION` 与 `AGENT_DECISION` 是模型可见的受控事实；`AGENT_CONTINUATION` 只作为运行元数据和前端折叠依据，不直接注入模型文本。

Runtime 的输入边界由 `AgentTurnExecutionRouter` 按 `MESSAGE`、`QUESTION_ANSWER`、`WORKFLOW_DECISION` 和 `ORDER_ACTION` 分派；`AgentTurnInputValidator` 与 `AgentTurnItemPayloads` 负责无副作用的规范化和兼容 payload 构造，新的写入路径通过 `AgentItemJournal` 追加并发布事实。Spring AI 协调器保留模型调用与受控 Tool 生命周期，订单 Tool 的参数解析、字段白名单和输出截断由 `SpringAiOrderToolSupport` 承担；QuestionCard schema 与 Workflow Checkpoint schema 分属各自 Core 模型。历史 `WORKFLOW_ANSWER` Turn 只按消息兼容读取，不进入新 Runtime 路径。这样拆分不改变同 Thread FIFO、持久化 Item、事务边界或外部动作幂等契约。

## 编排和审批

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

每次转换都会更新 `STEPS_JSON` 并追加 `WORKFLOW_STEP` Item。外部动作成功后的订单/物流核验发生在本地事务外；核验回执与 continuation Turn 的创建在本地事务中原子提交，提交后才进入 Runtime 队列。授权提交时若最新订单事实或执行资格已变化，也会在同一事务中收口为受控失败事实并触发续跑，不创建外部命令。续跑保留 `rootTurnId`、`parentTurnId`、触发 Run/Command/Sequence 和 `cycleNo`，使用触发事实生成确定性 `clientRequestId`，重启恢复和 Worker 重放不会产生重复 Turn。人工 QuestionCard/Checkpoint 子 Turn 按 Workflow 归属折回来源事项；后台 `RETRY_WAIT` 只等待 Worker，不唤醒模型。无法找到 Workflow owner 或事项锚点时，续跑安全停止并保留原始业务结果。

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

SSE 事件包含完整 envelope：`eventId、threadId、turnId、itemId（可选）、type、sequence、timestamp、payload`；公开类型收敛为 `ready`、`heartbeat` 和 `item.*`，`data` 不再只发送 payload。客户端按真实 `eventId/itemId/sequence` 去重。身份只从认证上下文读取，不信任请求体中的用户字段。

执行回放接口从同一组 Item 事实投影当前 Turn 的队列、上下文、Tool、Workflow、审批和外部动作时间线；回放过程不调用模型、不启动 Workflow，也不重放外部副作用。运行指标只保留低基数维度：队列等待、Turn/Tool 耗时、上下文预算、Workflow 等待、Worker 重试、Lease 接管和失败分类。`scripts.runtime_eval` 使用 Fake 协调器和 Fake 执行器做确定性门禁，Live Model 评测单独产出质量报告。

## 数据库

`docs/dev-ops/mysql/commerce-guardian-agent.sql` 是新库的破坏性基线；已有库必须先备份并由 `db/migration/V1__align_workflow_question_recovery.sql` 至 `V7__persist_agent_continuations.sql`、`V8__persist_langgraph_snapshots.sql`、`V9__split_question_cards_and_workflow_checkpoints.sql`、`V10__persist_context_compaction_metadata.sql`、`V11__persist_workflow_orchestration_version.sql`、`V12__persist_langgraph_orchestration_version.sql` 逐版本增量升级。V8 只增加可重建的 `AGENT_GRAPH_SNAPSHOT` 技术表，V9 将提问与执行确认拆为独立事实，V10 为上下文 V2 快照增加格式、来源范围、估算和摘要版本元数据，V11 为每个 `AGENT_WORKFLOW_RUN` 写入不可变的编排版本，V12 为图快照写入同样的不可变编排版本，并将历史记录归入 `LEGACY_V1`；历史业务事实和已有 Run 状态不被重写。旧 `AGENT_WORKFLOW_QUESTION`、Turn 中的旧回答列和旧 `WORKFLOW_ANSWER` 标记在保留期内只读，仅供迁移/历史投影使用，运行时代码不再映射或写入。基线保留这些历史列/表以支持迁移演练，同时创建当前 `AGENT_QUESTION_CARD`、`AGENT_WORKFLOW_CHECKPOINT`、`AGENT_GRAPH_SNAPSHOT`、`EXTERNAL_ACTION_COMMAND` 和 `EXTERNAL_ACTION_RESULT`。同一用户的同一来源 Turn 和 Workflow 类型只能有一个 WorkflowRun；迁移不得重建或覆盖已有业务事实。

2B-1 试点由 `AI_AGENT_EXPEDITE_GRAPH_ENABLED` 控制，默认关闭。开关打开后，只有明确订单号的催发货新 Run 标记为 `EXPEDITE_GRAPH_V1`；补选订单、其他订单操作和已有 Run 使用 `LEGACY_V1` 兼容路径。聊天 Tool 和订单卡片都进入同一个 `AgentWorkflowEngine` 路由。图节点依次记录订单读取、资格核验、确认等待和交给 Worker 的受控阶段；确认事务保存 Run、Checkpoint、开放交互指针和 Items，批准事务锁定并复核 Run/Checkpoint 后才创建唯一命令。恢复先校验 Run 编排版本，再读取 QuestionCard、Workflow Checkpoint 和订单事实；未知版本直接失败，不回退另一条路径。重复来源 Turn 返回原有交互，参数变化收口为冲突。试点创建命令后保持 `WAITING_EXTERNAL_ACTION`，Worker 继续负责外部执行与结果结算；图的技术 END 不投影为业务成功。

2B-2 的生产快照恢复代码已接入并完成一次性副本现场验收：`MybatisLangGraphCheckpointSaver` 按 Run 保存技术节点、状态、业务版本、事实指纹和编排版本，缺失/损坏/失配时由业务 Run、Checkpoint 和订单事实重建；快照不授予授权，也不替代业务事实。事实指纹使用跨 JVM 稳定的有序物流字段；未知编排版本以 `UNKNOWN_WORKFLOW_ORCHESTRATION_VERSION` 失败，不回退另一条路径。试点默认仍关闭，生产开关、第三方鉴权和删除动作按部署环境单独验收。

V6 现场迁移先备份配置库并在一次性克隆库执行；V7 首次运行前同样必须备份并在一次性克隆库验证。确认 `INPUT_KIND` 非空、`ORDER_ACTION_JSON` 和 `CONTINUATION_JSON` 可空，历史 Workflow 状态不被重写。外部 HTTP 订单服务、Agent 和前端验收结束后关闭测试进程，MySQL 保持运行。

本地现场复核可使用 `docs/review-runbook.md` 和 `scripts/review/review-services.ps1`。订单夹具通过 `ORDER_SERVICE_FIXTURE_EXPEDITE_TRANSIENT_FAILURES` 注入有限的催发货可重试失败，并在 `/_fixture/stats` 暴露注入次数；注入只持久化验收故障计数，不写入订单服务幂等记录或业务状态。

## Week 4 演示验收边界（2026-09）

`scripts.acceptance` 是本机验收工具，不是生产流量回放器。它先通过 `/api/agent` 验证 Thread → Turn → Item 的恢复、游标、开放交互、Turn 幂等和执行轨迹回放，再可选连接独立 SQLite 订单夹具验证物流、退款、催发货重试和显式授权的删除。夹具动作通过唯一 `Idempotency-Key` 重放并对照 `/_fixture/stats`；删除开关默认关闭，只允许在操作者确认数据库可丢弃后作用于固定演示订单。该工具不保存模型原文、Thinking、密钥或完整订单响应，也不改变 HTTP、SSE 或 Item 事实协议；V2 ContextSnapshot 和 Workflow 编排版本都是可校验的派生/路由元数据。

Week 4 的真实模型质量报告、数据库副本迁移和浏览器矩阵属于外部验收证据，不能由确定性 runner 或前端组件测试推断完成；本轮已在真实 Agent + 独立订单夹具上完成 HTTP Thread/Turn/Item、开放交互、幂等、执行回放、物流、退款、催发货重试和完整催发货黄金路径，并以合成订单完成真实 DeepSeek 查询、Worker 结果核验、模型续接总结和刷新恢复。四尺寸页面、移动抽屉/Escape、控制台无错误及深浅主题/reduced-motion smoke 已记录；`1536×730` 另完成离线/在线后的带游标 SSE 重订阅和 QuestionCard 错误焦点，其他尺寸专项证据仍按运行手册逐项补录。

## 阶段七验收状态（更新至 2026-09-13）

本轮规范检查、脚本测试、运行时确定性门禁、后端全量单测和前端 typecheck/Vitest/生产构建均通过；2A-2 的 Core/Infrastructure 回归覆盖裁剪、摘要、V2 快照和溢出恢复边界，2B-1/2B-2 增加试点图阶段、事实指纹、进程重启业务重建、锁读、命令幂等和技术快照故障恢复测试，事项级恢复与前端动作状态投影已补齐成功优先级。模块 `.env` 已加载到 Maven 测试进程，`context-acceptance` 与 `workflow-acceptance` profile 在随机临时库通过 V9→V12 Flyway 迁移、Run/图快照版本读取、归属隔离、事务回滚和 CAS 验收；HTTP acceptance runner、合成订单 DeepSeek 完整黄金路径、快照故障注入和 `1536×730` 浏览器 SSE/错误焦点专项均已通过。2026-09-12 LangGraph 定向回归 27/27 通过；2026-09-13 Codec/Journal 已接入 18 类 Core Item 值模型及 Runtime、Spring AI Tool、LangGraph、Worker Outcome、Continuation、订单事实、外部动作状态、执行事件、错误、QuestionCard/Workflow Decision 生产写入，Items API 新增兼容 `data` 字段，完整 reactor 与追加回归通过。删除动作、生产开关和第三方鉴权仍是部署环境门禁。
