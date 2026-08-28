status: active
updated: 2026-08-28

# Task Handoff

Goal:

- 完成 V7 整改与 Workflow 框架迁移：Spring AI 负责模型与 Tool Calling，LangGraph4j 负责确定性订单 Workflow；QuestionCard 只提问，Workflow Checkpoint 独立承载执行确认。
- 当前产品边界：Thread 不提供回收站/归档恢复入口；订单不隐藏或恢复，只提供经 Workflow 确认的 `DELETE_ORDER` 直接删除记录。

Completed:

- 阶段一至六已完成：LangGraph 基础门禁、QuestionCard/Checkpoint 拆分、七节点 Workflow、Continuation 加固、双卡片前端交互和旧运行时清理。
- `DELETE_ORDER` 已贯通订单卡片、Workflow `AUTHORIZE`、本地/HTTP 网关、Worker 和夹具；隐藏/恢复与 Thread 归档/回收站只保留历史只读兼容。
- Windows/JDK loopback 适配已撤销，保留 `JdkClientHttpRequestFactory`；宿主机临时目录配置可使应用和真实 HTTP IT 正常启动。
- 本轮代码评审修复并提交 `9abaa2b`：Agent QuestionCard 回答/取消生命周期、Thread 重命名运行时事实隔离、前端实时失败/重试恢复。
- 本地 `.hooks`/`.githooks` 审查入口已停用；`core.hooksPath` 未设置，规范检查只通过显式命令和 CI 执行。仓库已有 `github` 远端，并将本机 `remote.pushDefault` 设为 `github`；不在本地自动 push。

Decisions:

- 本文件是有界的“最新状态摘要 + 唯一下一步指针”，不累计提交列表、过程日志或重复验证；详细证据进入实施追踪、运行手册和 Git 历史。
- 每次更新覆盖全文：Goal 保留当前目标，Completed 只保留仍影响下一步的事实，Validation 只保留命令类别与结果；被新结果替代的内容直接删除。
- 业务 WorkflowRun、QuestionCard、Workflow Checkpoint 和 ExternalActionCommand 是事实源；LangGraph 快照只保存可重建技术状态。
- 旧数据库表/列和旧 Item 只读保留到迁移/数据保留期结束，运行时代码不得访问或创建旧授权 QuestionCard。
- 保留 `JdkClientHttpRequestFactory`，不为宿主机 Windows/JDK loopback 问题引入 `SimpleClientHttpRequestFactory` 或项目启动兼容代码；该问题只在用户级 Java 运行环境配置中处理。
- 当前用户授权本计划创建阶段性 Git commit，后续推送目标为 GitHub；本轮不执行 push。`.hooks`、`.githooks`、`.idea`、deployment、Docker 及其他无关改动不纳入本阶段；本机 `core.hooksPath` 和 `remote.pushDefault` 均为 Git 本地配置，不写入项目代码。

TODO:

- 阶段七：使用数据库副本完成 V7→V8→V9 迁移核验，运行真实模型配置和前端浏览器黄金路径，补齐最终验收证据。
- 浏览器不可逆删除点击须在动作前取得用户确认；GitHub PR/Codex 审查须在用户明确 push 或创建 PR 后单独验证。
- 阶段七全部完成后，覆盖本文件为 `status: completed` 快照。

Blocked:

- 本地代码门禁、真实 HTTP `*IT`、订单夹具和浏览器结构检查暂无阻塞。
- 数据库副本与真实模型尚未在本轮重新执行；不可逆删除点击和 GitHub PR/Codex 审查分别等待用户确认/推送。

Next action:

- 最新 JAR、订单夹具和前端已重新启动并可供用户手动验证；下一步按授权执行数据库副本、真实模型和浏览器黄金路径验收。

Validation:

- Maven：`mvn clean '-DskipTests=false' test` 通过，Core 49、Infrastructure 74、App 17，共 140 项；`mvn verify` 通过真实 HTTP `*IT` 9 项；`mvn dependency:analyze -DskipTests` 通过。
- 前端：typecheck、Vitest 38 项、production build 通过。
- Python：convention check、scripts tests 10 项和 runtime eval 通过。
- 最近一次浏览器结构复核无回收站/归档/恢复入口、订单删除入口存在、控制台无 warning/error；实际删除动作尚未点击。GitHub 远端可读，当前分支尚未推送。

Preserve:

- 不修改或提交 `.idea`、deployment、Docker、Hook、脚本及其他未纳入当前阶段的既有工作树改动。
- `agent-fronted` 是当前唯一前端目录；历史旧 Item 和旧数据库结构只用于迁移或只读展示。
