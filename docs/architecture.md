# Commerce Guardian Agent 架构

## 边界

Core 只表达 `Thread`、`Turn`、`Item`、QuestionCard、ContextSnapshot 和 ExternalActionCommand 的规则与端口；infrastructure 适配 MyBatis-Plus、Spring AI、订单夹具和外部动作；app 只处理配置、HTTP 协议和认证上下文。依赖方向固定为 `app → core`、`app → infrastructure`、`infrastructure → core`。

## V7 Workflow 框架决策（2026-08）

生产运行时采用 Spring AI + LangGraph4j 的窄边界混合架构：Spring AI 只负责模型调用、对话协调和 Tool Calling；LangGraph4j 1.8.20 只负责固定订单 Workflow 的图、条件边、循环、中断和技术恢复。Core 不依赖任何一个框架，业务 `WorkflowRun`、QuestionCard、Workflow Checkpoint 和 `ExternalActionCommand` 仍是唯一事实源。

不引入 LangChain4j、Embabel 或 Koog。LangChain4j 与 Spring AI 在模型、Tool、Memory 和 RAG 层重叠；Embabel 当前 Java 21 要求且动态 Goal Planning 不符合 JDK 17 与确定性写操作边界；Koog 会引入 Kotlin、协程、序列化及第二套 Agent/Tool/Persistence 运行时。重新评估条件仅限于：JDK 升级到 21、确定性 Workflow 边界被产品明确放弃，或 Spring AI/LangGraph4j 无法满足已验收的恢复与持久化契约；在此之前不得并行引入第二套 Agent 运行时。

LangGraph 的 `runId` 是技术 graph thread ID。项目内 `MybatisLangGraphCheckpointSaver` 只保存节点、下一节点、序列化状态、业务 WorkflowRun 版本和事实指纹到 `AGENT_GRAPH_SNAPSHOT`；不保存或决定业务授权。快照缺失或版本/指纹失配时，以业务 `WorkflowRun` 重建图状态。LangGraph4j 内置 AgentExecutor 和内置 MySQL Saver 不进入生产依赖路径，避免第二套业务事实表和 Jackson 2 序列化链。

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

Item 是唯一事实来源。每个 Item 的 `PAYLOAD_JSON` 使用 `schemaVersion=1` 和 `kind` 判别 envelope；`TURN_STATE` 记录 QUEUED、ACTIVE、WAITING、终态等生命周期事实，模型最终消息、工具调用/结果、Workflow 状态、订单动作请求和错误均即时持久化。模型内部可以流式消费，但 SSE 对外只发送 `ready`、`heartbeat` 和 `item.*`，不再暴露 `assistant.delta` 或瞬时 `turn.*`；客户端断线时先按 `afterSequence` 读取 Items，再订阅事件，不重放丢失的文本增量。

第一阶段生产路径显式关闭 `AgentContextAssembler` 的快照视图：从原始 Items 读取最新 300 条，保留库中的旧 `ContextSnapshot` 但不再据此跳过历史；每次组装记录 `estimatedTokens`、`inputBudget`、`snapshotThroughSequence`、`compressed`、`degraded` 和 `droppedItems`。系统提示、工具定义、历史、当前输入及本轮工具结果会在每次真实模型请求前重新估算，输出预留不计入输入预算。后续 2A 再单独启用快照压缩和摘要替换。`WORKFLOW_STEP`、`WORKFLOW_CHECKPOINT`、`WORKFLOW_DECISION` 与 `AGENT_DECISION` 是模型可见的受控事实；`AGENT_CONTINUATION` 只作为运行元数据和前端折叠依据，不直接注入模型文本。

Runtime 的输入边界由 `AgentTurnExecutionRouter` 按 `MESSAGE`、`QUESTION_ANSWER`、`WORKFLOW_DECISION` 和 `ORDER_ACTION` 分派；`AgentTurnInputValidator` 与 `AgentTurnItemPayloads` 只负责无副作用的规范化和 Item envelope 构造。Spring AI 协调器保留模型调用与受控 Tool 生命周期，订单 Tool 的参数解析、字段白名单和输出截断由 `SpringAiOrderToolSupport` 承担；QuestionCard schema 与 Workflow Checkpoint schema 分属各自 Core 模型。历史 `WORKFLOW_ANSWER` Turn 只按消息兼容读取，不进入新 Runtime 路径。这样拆分不改变同 Thread FIFO、持久化 Item、事务边界或外部动作幂等契约。

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
             → EXECUTE_ACTION → VERIFY_OUTCOME → HANDOFF_AGENT
```

每次转换都会更新 `STEPS_JSON` 并追加 `WORKFLOW_STEP` Item。外部动作成功后的订单/物流核验发生在本地事务外；核验回执与 continuation Turn 的创建在本地事务中原子提交，提交后才进入 Runtime 队列。授权提交时若最新订单事实或执行资格已变化，也会在同一事务中收口为受控失败事实并触发续跑，不创建外部命令。续跑保留 `rootTurnId`、`parentTurnId`、触发 Run/Command/Sequence 和 `cycleNo`，使用触发事实生成确定性 `clientRequestId`，重启恢复和 Worker 重放不会产生重复 Turn。

QuestionCard 回答使用 `POST /questions/{questionId}/answers`，请求体携带 `clientRequestId + expectedVersion + answers`，按 QuestionCard 的 `resumeTarget=AGENT|WORKFLOW` 恢复并作为同一 Thread 的新 Turn 进入 FIFO。Workflow Checkpoint 决策使用 `POST /workflow-runs/{runId}/checkpoints/{checkpointId}/decisions`，只接受批准或拒绝；批准时重新校验事实指纹，事实变化则标记 `SUPERSEDED` 并回到 `VERIFY_FACTS`。启动、交互创建和版本关闭受本地事务约束；同一 Thread 同时最多一个开放交互。退款仅允许 PAID/SHIPPED/DELIVERED，催发货仅允许 PAID；原始模型思考内容不进入 API、SSE、数据库或日志。

订单卡片使用确定性动作入口 `POST /threads/{threadId}/order-actions`。查询/刷新动作直接调用订单端口并追加结构化 `ORDER_*`/`LOGISTICS_TIMELINE` Item；退款、催发货和直接删除订单记录只启动已有 `ORDER_SERVICE` Workflow，确认前不创建外部动作命令、不调用模型。删除动作通过订单端口的 `DELETE /orders/{id}` 同步清理可删除物流轨迹且不可恢复；隐藏/恢复写入口已移除，旧隐藏字段仅可读兼容。动作请求同时保存在 Turn 的 `INPUT_KIND=ORDER_ACTION`、`ORDER_ACTION_JSON` 和 `ORDER_ACTION_REQUEST` Item 中，按 `clientRequestId` 幂等并校验来源 Turn、订单归属和 Thread FIFO。Workflow 回答子 Turn 通过 `sourceTurnId`/`runId` 折回来源 Turn，技术 Turn 仅在 Item 检查器中展开。

## Runtime 可靠性

队列键是 Thread：同一 Thread 严格 FIFO、任意时刻一个 ACTIVE Turn；不同 Thread 可并行。Turn 持久状态通过 `VERSION_NO` 条件更新执行 CAS，版本竞争或终态重写会丢弃后续事实写入。排队 Turn 可直接取消，ACTIVE Turn 通过运行上下文协作取消；已提交的外部副作用不会回滚。每次模型请求都检查完整上下文并预留输出额度，完成后只结算一次 usage；usage 缺失、零值或断流保留整笔预留。默认应用上下文总预算为 65,536 估算 token，输入预算扣除 1,500 预留，单次模型输出上限为 1,024，Turn 累计生成额度为 8,192，截止时间为 4 分钟。相同工具、规范化参数和稳定错误码连续失败 3 次后以 `FALLBACK` 结束并写入 `TOOL_REPEATED_FAILURE`；成功或不同失败组合会重置计数。资源停止使用 `STOP_LIMIT`，代码为 `CONTEXT_BUDGET_EXCEEDED` 或 `OUTPUT_BUDGET_EXCEEDED`，前端不把它投影为业务成功。队列等待、Turn、工具、外部动作和 SSE 流/心跳均可独立配置。

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

`docs/dev-ops/mysql/commerce-guardian-agent.sql` 是新库的破坏性基线；已有库必须先备份并由 `db/migration/V1__align_workflow_question_recovery.sql` 至 `V7__persist_agent_continuations.sql`、`V8__persist_langgraph_snapshots.sql`、`V9__split_question_cards_and_workflow_checkpoints.sql` 逐版本增量升级。V8 只增加可重建的 `AGENT_GRAPH_SNAPSHOT` 技术表，V9 将提问与执行确认拆为独立事实；历史业务事实和 `AGENT_WORKFLOW_RUN` 不被重写。旧 `AGENT_WORKFLOW_QUESTION`、Turn 中的旧回答列和旧 `WORKFLOW_ANSWER` 标记在保留期内只读，仅供迁移/历史投影使用，运行时代码不再映射或写入。基线保留这些历史列/表以支持迁移演练，同时创建当前 `AGENT_QUESTION_CARD`、`AGENT_WORKFLOW_CHECKPOINT`、`AGENT_GRAPH_SNAPSHOT`、`EXTERNAL_ACTION_COMMAND` 和 `EXTERNAL_ACTION_RESULT`。同一用户的同一来源 Turn 和 Workflow 类型只能有一个 WorkflowRun；迁移不得重建或覆盖已有业务事实。

V6 现场迁移先备份配置库并在一次性克隆库执行；V7 首次运行前同样必须备份并在一次性克隆库验证。确认 `INPUT_KIND` 非空、`ORDER_ACTION_JSON` 和 `CONTINUATION_JSON` 可空，历史 Workflow 状态不被重写。外部 HTTP 订单服务、Agent 和前端验收结束后关闭测试进程，MySQL 保持运行。

本地现场复核可使用 `docs/review-runbook.md` 和 `scripts/review/review-services.ps1`。订单夹具通过 `ORDER_SERVICE_FIXTURE_EXPEDITE_TRANSIENT_FAILURES` 注入有限的催发货可重试失败，并在 `/_fixture/stats` 暴露注入次数；注入只持久化验收故障计数，不写入订单服务幂等记录或业务状态。

## Week 4 演示验收边界（2026-09）

`scripts.acceptance` 是本机验收工具，不是生产流量回放器。它先通过 `/api/agent` 验证 Thread → Turn → Item 的恢复、游标、开放交互、Turn 幂等和执行轨迹回放，再可选连接独立 SQLite 订单夹具验证物流、退款、催发货重试和显式授权的删除。夹具动作通过唯一 `Idempotency-Key` 重放并对照 `/_fixture/stats`；删除开关默认关闭，只允许在操作者确认数据库可丢弃后作用于固定演示订单。该工具不保存模型原文、Thinking、密钥或完整订单响应，也不改变现有 HTTP、SSE 或 V9 数据结构。

Week 4 的真实模型质量报告、数据库副本迁移和四尺寸浏览器矩阵属于外部验收证据，不能由确定性 runner 或前端组件测试推断完成；缺少对应服务、凭据或操作者确认时应明确记录为 pending。

## 阶段七验收状态（2026-08-28）

本轮本地验收已通过规范检查、脚本测试、运行时确定性门禁、后端全量单测和前端 typecheck/Vitest/生产构建。`mvn verify` 已单独运行真实 HTTP `*IT`，`HttpOrderGatewayIT` 与 `HttpExternalActionExecutorIT` 共 9 项通过；Windows/JDK 的 loopback 环境问题已通过宿主机用户级 Java 临时目录配置恢复，项目未引入协议替代实现。应用加载真实 `.env` 后完成 MySQL、Flyway V9 和 Tomcat 初始化，健康检查为 `200 UP`。数据库副本 V7→V8→V9、真实模型和真实浏览器黄金路径仍需按阶段七运行手册重新复核，不能以历史现场记录替代本轮证据。
