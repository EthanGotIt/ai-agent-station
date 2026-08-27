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

Decisions:

- 不引入 LangChain4j、Embabel 或 Koog；不使用 LangGraph4j AgentExecutor 或内置 MySQL Saver。
- 业务 WorkflowRun、QuestionCard、Workflow Checkpoint 和 ExternalActionCommand 是业务事实源；LangGraph 快照只保存可重建的技术状态。
- `runId` 作为 LangGraph technical thread ID；技术快照失配时从业务 WorkflowRun 重建。
- 当前用户明确授权本计划提交 Git commit，不执行 push。

TODO:

- 完成阶段二至七的交互契约、固定订单 Workflow、Continuation 加固、前端投影、遗留清理和完整验收。

Blocked:

- 暂无。若 LangGraph4j 与 JDK 17/Jackson 3/MyBatis 编译门禁不兼容，先记录阻塞并停止生产 Workflow 切换。

Next action:

- 开始阶段二：新增独立 `AgentQuestionCardModel/Store` 与 `AgentWorkflowCheckpointModel/Store`，更新 Thread 开放交互引用和 V9 迁移。

Validation:

- 本阶段开始前已确认 JDK 17、Spring Boot 4.1、Spring AI 2.0、MyBatis 和 Jackson 3 的现有构建基线。
- `mvn -q -pl commerce-guardian-agent-infrastructure -am -DskipTests compile` 通过。
- `mvn -q -pl commerce-guardian-agent-infrastructure -am '-Dtest=*LangGraph*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过（4 项）。
- `mvn -q dependency:analyze '-DskipTests'` 通过。

Preserve:

- 不修改或提交 `.idea`、deployment、Docker、Hook、脚本及其他未纳入当前阶段的既有工作树改动。
- 旧 `AGENT_WORKFLOW_QUESTION` 数据表在迁移保留期内只读，运行时代码在后续阶段停止访问。
