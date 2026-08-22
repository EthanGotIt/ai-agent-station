# Commerce Guardian Agent 架构

## 边界

Core 只表达 `Thread`、`Turn`、`Item`、QuestionCard、ContextSnapshot 和 ExternalActionCommand 的规则与端口；infrastructure 适配 MyBatis-Plus、Spring AI、订单夹具和外部动作；app 只处理配置、HTTP 协议和认证上下文。依赖方向固定为 `app → core`、`app → infrastructure`、`infrastructure → core`。

## Agent-first 分包

Maven 模块负责依赖隔离，Java package 负责能力内聚。能力是第一维度，具体技术只出现在叶子适配器包，不使用按 `model/service/mapper/controller` 横向切开的全局技术层。

```text
core
├── agent.thread          # Thread、Turn、Item 事实和存储契约
├── agent.execution       # FIFO、取消、超时、恢复和 Turn 执行
├── agent.context         # 上下文预算、快照和摘要
├── agent.coordination    # 协调 Agent 输入输出契约
├── agent.workflow        # WorkflowRun、QuestionCard、Checkpoint
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
│   ├── WORKFLOW_STARTED / WORKFLOW_QUESTION / WORKFLOW_ANSWER
│   ├── EXTERNAL_ACTION_STATUS
│   └── ASSISTANT_MESSAGE / ERROR
└── ContextSnapshot（截至某个 sequence 的版本化摘要）
```

Item 是唯一事实来源。每个 Item 的 `PAYLOAD_JSON` 使用 `schemaVersion=1` 和 `kind` 判别 envelope；`TURN_STATE` 记录 QUEUED、ACTIVE、WAITING、终态等生命周期事实。文本增量只通过 SSE 发送，完成消息、工具调用、工具结果、Workflow 状态和错误才持久化。客户端断线时先按 `afterSequence` 读取 Items，再订阅事件，不重放丢失的增量文本。

`AgentContextAssembler` 从最新快照的 `throughSequence` 继续读取，过滤当前 Turn 已写入的输入，依次放入系统提示和工具定义、快照之后的最近 Item、当前输入，并为输出预留预算。超过阈值时通过 `AgentContextSummarizer` 压缩最旧已完成 Turn；摘要失败沿用旧快照和最近窗口，`AgentContextBudgetReport` 标记降级但不阻塞执行。所有预算和工具结果截断上限均配置化。

## 编排和审批

`SpringAiAgentTurnCoordinator` 是唯一协调 Agent。只读 Tool 查询订单和物流；订单售后能力统一由 `start_order_service_workflow` 启动确定性 Workflow，不能直接产生外部副作用。Tool Call/Result 只记录受控参数、状态、截断标志，不记录 Prompt 或 Thinking。Workflow 类型、状态和 QuestionCard 状态使用枚举，并显式执行：

```text
校验 → 持久化 QuestionCard → WAITING_USER_INPUT
     → 用户回答 → 本地事务创建 ExternalActionCommand
     → Worker 执行 → SUCCEEDED / MANUAL_RETRY_REQUIRED
```

回答路径参数携带 `runId + questionId`，请求体只携带 `clientRequestId + checkpointId + expectedVersion + answers`，并作为同一 Thread 的新 Turn 进入 FIFO。启动、问题创建和版本关闭受本地事务约束；同一 Thread 同时最多一个开放 QuestionCard。退款仅允许 PAID/SHIPPED/DELIVERED，催发货仅允许 PAID；原始模型思考内容不进入 API、SSE、数据库或日志。

## Runtime 可靠性

队列键是 Thread：同一 Thread 严格 FIFO、任意时刻一个 ACTIVE Turn；不同 Thread 可并行。Turn 持久状态通过 `VERSION_NO` 条件更新执行 CAS，版本竞争或终态重写会丢弃后续事实写入。排队 Turn 可直接取消，ACTIVE Turn 通过运行上下文协作取消；已提交的外部副作用不会回滚。队列等待、Turn、工具、外部动作和 SSE 流/心跳均可独立配置。

外部命令以 `(userId, idempotencyKey)` 唯一。Worker 先在本地事务中原子 Claim Lease，再在事务外调用远程系统；PENDING、RETRY_WAIT 和过期 PROCESSING 都可被领取。仅瞬时错误按指数退避，永久错误直接进入人工重试；动作超时也会形成可分类结果。订单 HTTP 适配器必须把命令的 `idempotencyKey` 作为 `Idempotency-Key` 请求头传给退款、催发货和隐藏/恢复接口，由订单服务按该键去重；本地演示执行器则在同一本地事务中完成订单状态 CAS 和幂等回执写入。人工重试沿用原命令和幂等键，重复回答、重复 Claim 和重启恢复不会产生第二次业务写入。

## HTTP 和事件

唯一 API 前缀为 `/api/agent`：

```text
POST   /threads
GET    /threads?page=0&size=20
GET    /threads/{threadId}
PATCH  /threads/{threadId}
GET    /threads/{threadId}/items?afterSequence=0&limit=200
POST   /threads/{threadId}/turns
POST   /turns/{turnId}/cancel
GET    /threads/{threadId}/events
GET    /turns/{turnId}/execution
POST   /workflow-runs/{runId}/questions/{questionId}/answers
POST   /workflow-runs/{runId}/retry
```

SSE 事件包含完整 envelope：`eventId、threadId、turnId、itemId（可选）、type、sequence、timestamp、payload`；`data` 不再只发送 payload。客户端按真实 `eventId/itemId/sequence` 去重。身份只从认证上下文读取，不信任请求体中的用户字段。

执行回放接口从同一组 Item 事实投影当前 Turn 的队列、上下文、Tool、Workflow、审批和外部动作时间线；回放过程不调用模型、不启动 Workflow，也不重放外部副作用。运行指标只保留低基数维度：队列等待、Turn/Tool 耗时、上下文预算、Workflow 等待、Worker 重试、Lease 接管和失败分类。`scripts.runtime_eval` 使用 Fake 协调器和 Fake 执行器做确定性门禁，Live Model 评测单独产出质量报告。

## 数据库

`docs/dev-ops/mysql/commerce-guardian-agent.sql` 是新库的破坏性基线；已有库必须先备份并由 `db/migration/V1__align_workflow_question_recovery.sql`、`V2__expand_order_search_fields.sql`、`V3__support_multi_step_order_workflow.sql`、`V4__index_order_service_actions.sql` 和 `V5__align_legacy_state_schema.sql` 逐版本增量升级。V5 专门兼容早期已部署库缺少的 Thread/Turn/ExternalAction 状态字段、结果表和幂等约束，不删除或重建业务事实。基线包含演示订单/物流和 `AGENT_THREAD`、`AGENT_TURN`、`AGENT_ITEM`、`AGENT_CONTEXT_SNAPSHOT`、`AGENT_WORKFLOW_RUN`、`AGENT_WORKFLOW_QUESTION`、`EXTERNAL_ACTION_COMMAND`、`EXTERNAL_ACTION_RESULT`。同一用户的同一来源 Turn 和 Workflow 类型只能有一个 WorkflowRun；迁移不得重建或覆盖已有业务事实。
