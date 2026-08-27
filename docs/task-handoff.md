status: active
updated: 2026-08-27

# Task Handoff

Goal:

- 完成 V7 整改与 Workflow 框架迁移：Spring AI 负责模型与 Tool Calling，LangGraph4j 负责确定性订单 Workflow；拆分 QuestionCard 与 Workflow Checkpoint，并清理旧运行时路径。

Completed:

- 已确认采用 Spring AI + LangGraph4j 1.8.20 的混合架构。
- 已确认 QuestionCard 只表达 `request_user_input`；固定 Workflow 的人工执行确认使用独立 Checkpoint。
- 已保留当前工作树中的用户既有改动，阶段提交只按明确路径隔离。
- 已完成 LangGraph4j 1.8.20 依赖门禁、`AGENT_GRAPH_SNAPSHOT` V8 迁移、Jackson 3 状态序列化、MyBatis Checkpoint Saver 和七节点图骨架。
- 已验证固定拓扑、条件循环、最大迭代、技术快照保存/覆盖/恢复和 JDK 17 + Spring Boot 4.1 + Spring AI 2.0 + MyBatis 构建兼容性。
- 已完成阶段二的 QuestionCard/Workflow Checkpoint Core 模型、状态 CAS、独立 MyBatis 持久化表、V9 迁移和 Thread 单开放交互指针。
- 已新增 `QUESTION_ANSWER`、`WORKFLOW_DECISION` Turn 输入与 Item，事务 admission 统一完成回答/决策 Turn 的首事实持久化和 FIFO 入队。
- 已新增 `/threads/{threadId}/interaction`、`/questions/{questionId}/answers` 和 `/workflow-runs/{runId}/checkpoints/{checkpointId}/decisions` API。
- Spring AI 已增加 `request_user_input` 终态 Tool；`complete_agent_cycle` 新契约使用 `FINISH/ASK_USER`，提问不再使用授权字段。
- 已完成固定订单 Workflow 的 LangGraph4j 迁移：`RESOLVE_ORDER → VERIFY_FACTS → SWITCH_REQUIREMENTS → AUTHORIZE → EXECUTE_ACTION → VERIFY_OUTCOME → HANDOFF_AGENT`。
- 新引擎在 `AUTHORIZE` 前中断；缺订单/原因创建 Workflow QuestionCard，写动作创建独立 Workflow Checkpoint，批准后才创建 `ExternalActionCommand`。
- 旧事务推进引擎已退出生产 Spring 装配，仅保留兼容测试和历史回放边界；技术快照失配可从业务 WorkflowRun 重建。
- 已补齐 QuestionCard/Checkpoint 中断恢复、事实指纹变化回到 `VERIFY_FACTS`、外部命令等待和 LangGraph 拓扑回归测试。
- 已完成 Continuation admission 收口：`AgentContinuationGateway.admit` 统一根 Turn、父 Turn、WorkflowRun、ExternalActionCommand、结果状态、Item Sequence 和 cycle 幂等键。
- Continuation Turn 与首个 `AGENT_CONTINUATION` Item 在同一本地事务中持久化，提交后才进入 Thread FIFO；重复回调复用既有 Turn，队列失败由恢复扫描兜底。
- 已将运行时的 `continuation-enabled` 与 `max-agent-cycles` 配置接入 Spring Boot，最大自动轮次限制为 1–5（默认 3），达到上限写入 `STOP_LIMIT` 决策而不创建新 Turn。
- `ExternalActionOutcomeManager` 已改为调用统一 Gateway，完成/人工重试结果会投影 `VERIFY_OUTCOME`、`HANDOFF_AGENT` 和 Continuation 事实；未满足触发条件的失败结果不创建续跑。

Decisions:

- 不引入 LangChain4j、Embabel 或 Koog；不使用 LangGraph4j AgentExecutor 或内置 MySQL Saver。
- 业务 WorkflowRun、QuestionCard、Workflow Checkpoint 和 ExternalActionCommand 是业务事实源；LangGraph 快照只保存可重建的技术状态。
- `runId` 作为 LangGraph technical thread ID；技术快照失配时从业务 WorkflowRun 重建。
- Continuation 的持久化 Turn 是可恢复事实，队列只承担调度；幂等键必须包含根/父 Turn、Run、Command、状态、结果 Sequence 和 cycle。
- 当前用户明确授权本计划提交 Git commit，不执行 push。

TODO:

- 阶段五：完成 QuestionCard 与 Workflow Checkpoint 的独立前端投影、取消/刷新恢复和长 Thread 增量更新。
- 阶段六：清理旧授权 QuestionCard、旧 Workflow 入口和无调用兼容代码，HTTP 单测切换 Fake Transport。
- 阶段七：执行数据库副本迁移、真实模型/订单夹具/前端黄金路径验收，更新架构与运行手册并关闭任务。

Blocked:

- 暂无。若 LangGraph4j 与 JDK 17/Jackson 3/MyBatis 编译门禁不兼容，先记录阻塞并停止生产 Workflow 切换。

Next action:

- 开始阶段五：审计前端目录与现有 Item 投影，分别实现问题卡和 Workflow 执行确认卡。

Validation:

- 本阶段开始前已确认 JDK 17、Spring Boot 4.1、Spring AI 2.0、MyBatis 和 Jackson 3 的现有构建基线。
- `mvn -q -pl commerce-guardian-agent-infrastructure -am -DskipTests compile` 通过。
- `mvn -q -pl commerce-guardian-agent-infrastructure -am '-Dtest=*LangGraph*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过（4 项）。
- `mvn -q dependency:analyze '-DskipTests'` 通过。
- 阶段二定向测试通过：QuestionCard/Checkpoint Core 状态机、Item payload、QuestionCard/Checkpoint MyBatis Store、Turn QuestionAnswer 持久化、`request_user_input` 工具。
- `mvn -q -pl commerce-guardian-agent-app -am -DskipTests compile` 通过；阶段二新增 API、admission 和 MapperScan 编译通过。
- 阶段三定向测试通过：LangGraph 图工厂 4 项、状态序列化 1 项、MyBatis Saver 1 项、LangGraph Workflow 引擎 2 项、Spring AI 协调/Tool 边界及旧引擎回归共 24 项；Infrastructure 和 App 编译通过。
- 阶段四定向测试通过：`AgentContinuationInputTest` 1 项、`TransactionalAgentContinuationGatewayTest` 6 项（成功、非终态失败、重复、并发、STOP_LIMIT、禁用）、`ExternalActionWorkerTest` 5 项、`AgentRuntimePropertiesTest` 5 项；Infrastructure 与 App 编译通过。
- 阶段三完整后端测试仍受现有 HTTP loopback 单测环境限制；`HttpExternalActionExecutorTest`、`HttpOrderGatewayTest`、`HttpLogisticsGatewayTest` 需要在阶段六切换 Fake Transport 后重新验收。

Preserve:

- 不修改或提交 `.idea`、deployment、Docker、Hook、脚本及其他未纳入当前阶段的既有工作树改动。
- 旧 `AGENT_WORKFLOW_QUESTION` 数据表在迁移保留期内只读，运行时代码在后续阶段停止访问。
- 当前工作树中的 `agent-console` 删除与 `agent-fronted` 未跟踪目录属于既有前端迭代；阶段五先核对其边界，再明确暂存路径，不在阶段四提交中混入。
