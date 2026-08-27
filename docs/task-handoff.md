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
- 已修复规范门禁发现的 persistence 包、Clock 注入和测试命名问题；阶段六整改与该独立修复均已提交。
- 本轮代码评审已收口续跑重试的过期 Turn 快照、批准后事实变化的 Checkpoint 失效/重核验、Agent QuestionCard 的空 `runId` 前端解析、重复 Workflow Answer 类型，以及事务代理和 Jackson 决策编解码器的启动装配问题；对应修复已按独立 `fix:`/`refactor:` 提交。
- 本轮本地验收已通过 convention、脚本 9 项、runtime eval 5 项、`mvn clean '-DskipTests=false' test`（core 47、infrastructure 65、app 17）、前端 typecheck/Vitest 31 项、production build 和无警告的 `mvn dependency:analyze -DskipTests`。

Decisions:

- 本文件是“最新状态快照 + 唯一下一步指针”，不累计提交列表或过程日志；历史提交以 Git 历史为准，详细证据进入实施追踪和验收文档。
- 业务 WorkflowRun、QuestionCard、Workflow Checkpoint 和 ExternalActionCommand 是事实源；LangGraph 快照只保存可重建技术状态。
- 旧数据库表/列和旧 Item 只读保留到迁移/数据保留期结束，运行时代码不得访问或创建旧授权 QuestionCard。
- 按当前验收范围保留 `JdkClientHttpRequestFactory`；Windows/JDK 内部 loopback pipe 报错只作为环境限制记录，不为其引入 `SimpleClientHttpRequestFactory` 生产规避，也不在本轮继续启动服务。
- 当前用户授权本计划创建阶段性 Git commit，不执行 push。

TODO:

- 阶段七：在支持网络绑定的环境重跑真实 `*IT`，使用数据库副本完成 V7→V8→V9 迁移核验，运行真实配置/订单夹具/前端黄金路径，补齐最终验收证据。
- 所有阶段七验收完成后，才覆盖 handoff 为 `status: completed` 快照。

Blocked:

- 本地代码门禁暂无阻塞；此前 `mvn verify` 的 8 项真实 HTTP `*IT` 因当前 Windows/JDK 无法建立 loopback selector 未通过，按本轮范围忽略该环境限制。真实数据库副本、模型服务和浏览器黄金路径也尚未在本轮重新执行。

Next action:

- 在可绑定 loopback 的环境重跑真实 `*IT`，再按运行手册执行数据库副本、真实模型/订单夹具/浏览器黄金路径验收，并据此覆盖本快照。

Validation:

- `mvn clean '-DskipTests=false' test`：通过。
- `mvn dependency:analyze -DskipTests`：通过，Core、Infrastructure、App 均无依赖问题。
- Python convention、脚本 9 项、runtime eval 5 项：通过。
- HTTP Fake Transport 定向测试、LangGraph/QuestionCard/Checkpoint/Continuation 定向测试：通过；本轮额外覆盖过期续跑重试、批准 Checkpoint 事实变化和 Agent QuestionCard `runId: null`。
- `mvn verify` 已进入真实 `*IT`，但 8 项 loopback selector 错误待支持网络绑定的环境复核；前端 typecheck、Vitest 31 项和生产构建：通过。

Preserve:

- 不修改或提交 `.idea`、deployment、Docker、Hook、脚本及其他未纳入当前阶段的既有工作树改动。
- `agent-fronted` 是当前唯一前端目录；历史旧 Item 和旧数据库结构只用于迁移或只读展示。
