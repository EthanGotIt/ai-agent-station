status: active
updated: 2026-08-27

# Task Handoff

Goal:

- 完成 V7 整改与 Workflow 框架迁移：Spring AI 负责模型与 Tool Calling，LangGraph4j 负责确定性订单 Workflow；QuestionCard 只提问，Workflow Checkpoint 独立承载执行确认，并清理旧运行时路径。

Completed:

- 已完成 LangGraph4j 基础门禁、QuestionCard/Workflow Checkpoint 拆分、固定七节点 Workflow、Continuation 生命周期加固和前端双卡片交互。
- 已删除旧 Workflow Question/Answer Core 模型、Store、admission、failure reconciler、事务推进引擎、旧 HTTP DTO、旧控制器入口和旧授权兼容构造器。
- 运行时只读取新的 QuestionCard、Workflow Checkpoint、Question Answer 和 Workflow Decision；历史 `WORKFLOW_QUESTION`/`WORKFLOW_ANSWER` 仅保留迁移与只读投影边界。
- 已将 HTTP 协议单测改为 Fake Transport，并把真实 loopback 测试重命名为 `*IT`；Maven Surefire 只运行 `*Test`，Failsafe 负责 `*IT`。
- 已清理生产代码、迁移说明、架构/运行手册和脚本中的旧前端与旧授权运行时引用。
- `mvn clean '-DskipTests=false' test` 通过：core 45、infrastructure 62、app 17；`mvn dependency:analyze -DskipTests` 构建通过。

Decisions:

- 本文件是“最新状态快照 + 唯一下一步指针”，不累计提交列表或过程日志；历史提交以 Git 历史为准，详细证据进入实施追踪和验收文档。
- 业务 WorkflowRun、QuestionCard、Workflow Checkpoint 和 ExternalActionCommand 是事实源；LangGraph 快照只保存可重建技术状态。
- 旧数据库表/列和旧 Item 只读保留到迁移/数据保留期结束，运行时代码不得访问或创建旧授权 QuestionCard。
- 当前用户授权本计划创建阶段性 Git commit，不执行 push。

TODO:

- 阶段七：使用数据库副本完成 V7→V8→V9 迁移核验，运行真实配置/订单夹具/前端黄金路径，补齐最终验收证据并关闭任务。
- 阶段七完成后覆盖 handoff 为 `status: completed` 快照。

Blocked:

- 本地代码门禁暂无阻塞；真实数据库副本、模型服务和可绑定 loopback 的环境尚未在当前验收会话中重新执行。

Next action:

- 进入阶段七，运行 Python convention/脚本测试、前端 typecheck/Vitest/build，并核对真实配置验收边界后更新架构、运行手册和实施追踪。

Validation:

- `mvn clean '-DskipTests=false' test`：通过。
- `mvn dependency:analyze -DskipTests`：构建通过；保留既有 `jspecify`/`spring-beans` 声明依赖警告供后续依赖整理。
- HTTP Fake Transport 定向测试、LangGraph/QuestionCard/Checkpoint/Continuation 定向测试：通过。

Preserve:

- 不修改或提交 `.idea`、deployment、Docker、Hook、脚本及其他未纳入当前阶段的既有工作树改动。
- `agent-fronted` 是当前唯一前端目录；历史旧 Item 和旧数据库结构只用于迁移或只读展示。
