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

`AgentContextAssembler` 依次放入系统提示和工具定义、最新快照、快照之后的最近 Item、当前输入，并为输出预留预算。超过阈值时压缩最旧已完成 Turn；摘要失败沿用旧快照和最近窗口，不阻塞执行。所有预算和工具结果截断上限均配置化。

## 编排和审批

`SpringAiAgentTurnCoordinator` 是唯一协调 Agent。只读 Tool 查询订单和物流；能力 Tool 只能启动退款或催发货 Workflow，不能直接产生外部副作用。Workflow 显式执行：

```text
校验 → 持久化 QuestionCard → WAITING_USER_INPUT
     → 用户回答 → 本地事务创建 ExternalActionCommand
     → Worker 执行 → SUCCEEDED / MANUAL_RETRY_REQUIRED
```

回答必须携带 `runId + questionId + checkpointId + expectedVersion`，并作为同一 Thread 的新 Turn 进入 FIFO。一个 Thread 同时最多一个开放 QuestionCard。原始模型思考内容不进入 API、SSE、数据库或日志。

## Runtime 可靠性

队列键是 Thread：同一 Thread 严格 FIFO、任意时刻一个 ACTIVE Turn；不同 Thread 可并行。排队 Turn 可直接取消，ACTIVE Turn 通过运行上下文协作取消；已提交的外部副作用不会回滚。队列等待、Turn、工具、外部动作和 SSE 各有独立超时。

外部命令以 `(userId, idempotencyKey)` 唯一。Worker 先在本地事务中原子 Claim Lease，再在事务外调用远程系统；仅瞬时错误有限退避，最终失败转人工重试。人工重试沿用原命令和幂等键，重复回答、重复 Claim 和重启恢复不会产生第二次业务写入。

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
POST   /workflow-runs/{runId}/questions/{questionId}/answers
POST   /workflow-runs/{runId}/retry
```

SSE 事件包含完整 envelope：`eventId、threadId、turnId、itemId（可选）、type、sequence、timestamp、payload`；`data` 不再只发送 payload。客户端按真实 `eventId/itemId/sequence` 去重。身份只从认证上下文读取，不信任请求体中的用户字段。

## 数据库

`docs/dev-ops/mysql/commerce-guardian-agent.sql` 是唯一破坏性基线，包含演示订单/物流和 `AGENT_THREAD`、`AGENT_TURN`、`AGENT_ITEM`、`AGENT_CONTEXT_SNAPSHOT`、`AGENT_WORKFLOW_RUN`、`AGENT_WORKFLOW_QUESTION`、`EXTERNAL_ACTION_COMMAND`。没有运行时建表，也没有增量升级脚本。
