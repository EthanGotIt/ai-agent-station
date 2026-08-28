status: active
updated: 2026-08-28

# Task Handoff

Goal:

- 完成 V7 整改与 Workflow 框架迁移：Spring AI 负责模型与 Tool Calling，LangGraph4j 负责确定性订单 Workflow；QuestionCard 只提问，Workflow Checkpoint 独立承载执行确认。
- 当前产品边界：Thread 不提供回收站/归档恢复入口；订单不隐藏或恢复，只提供经 Workflow 确认的 `DELETE_ORDER` 直接删除记录。

Completed:

- 阶段一至六已完成：LangGraph 基础门禁、QuestionCard/Checkpoint 拆分、七节点 Workflow、Continuation 加固、双卡片前端交互和旧运行时清理。
- `DELETE_ORDER` 已贯通订单卡片、Workflow `AUTHORIZE`、本地/HTTP 网关、Worker 和夹具；隐藏/恢复与 Thread 归档/回收站只保留历史只读兼容。
- Windows/JDK loopback 适配已撤销，保留 `JdkClientHttpRequestFactory`；宿主机临时目录和 Java 运行参数只在用户环境处理，不在项目代码中绕过。
- 本轮代码评审已修复：Workflow 业务失败/事实变化不会被决策 Turn 的 `COMPLETED` 覆盖；多个订单动作按 Turn/Run 隔离；历史分页、时间线和 SSE 回放在重复/乱序/无效页时收口；SSE 订阅/心跳调度失败会释放资源；Checkpoint 决策校验交互类型并保证开放指针 CAS 清理；QuestionCard 关闭校验交互类型。
- 本轮修复已按独立意图提交：前端业务结果投影、执行历史回放保护、Checkpoint 指针一致性。
- `.hooks`/`.githooks` 审查入口已停用；代码审查和合并门禁由推送后的 GitHub PR/CI 负责，本地不自动 push。

Decisions:

- 本文件是有界的“最新状态摘要 + 唯一下一步指针”，不累计提交列表、过程日志或重复验证；详细证据进入实施追踪、运行手册和 Git 历史。
- 每次更新整体覆盖文件：Goal 保留当前目标，Completed 只保留仍影响下一步的事实，Validation 只保留最近有效结果；被新结果替代的内容直接删除。
- 业务 WorkflowRun、QuestionCard、Workflow Checkpoint 和 ExternalActionCommand 是事实源；LangGraph 快照只保存可重建技术状态。
- 旧数据库表/列和旧 Item 只读保留到迁移/数据保留期结束，运行时代码不得访问或创建旧授权 QuestionCard。
- 当前用户授权本计划创建阶段性 Git commit，后续推送目标为 GitHub；本轮不执行 push。`.hooks`、`.githooks`、`.idea`、deployment、Docker 及其他无关改动不纳入本阶段；本机 Git 配置不写入项目代码。
- `AGENTS.md` 是完整长期协作规范，不按本文件的快照机制压缩或覆盖；本轮只做可审阅的定点优化。

TODO:

- 阶段七：使用数据库副本完成 V7→V8→V9 迁移核验，运行真实模型配置和前端浏览器黄金路径，补齐最终验收证据。
- GitHub PR/Codex 审查须在用户明确 push 或创建 PR 后单独验证；不可逆删除动作须在动作前取得用户确认。
- 阶段七全部完成后，覆盖本文件为 `status: completed` 快照。

Blocked:

- 本地代码门禁、真实 HTTP `*IT`、订单夹具和静态前端检查暂无阻塞。
- 数据库副本、真实模型、实际服务启动和浏览器黄金路径尚未在本轮重新执行；它们需要对应运行环境和用户确认/推送，不构成当前代码修复阻塞。

Next action:

- 在具备数据库副本、真实模型和浏览器运行条件后执行阶段七；当前本地代码评审和自动化门禁已完成，服务未保持运行状态。

Validation:

- Maven：`mvn clean '-DskipTests=false' test` 通过，148 项 0 失败；`mvn verify` 通过 HTTP `*IT` 9 项 0 失败；`mvn dependency:analyze -DskipTests` 通过。
- 前端：`npm --prefix agent-fronted run typecheck`、Vitest 5 个文件 47 项和 `npm --prefix agent-fronted run build` 通过。
- Python：`python -m scripts.convention_check`、脚本单测 10 项和 runtime eval 通过（本机使用 Miniconda Python 执行）。
- 未执行：真实数据库副本、真实模型、浏览器交互和 GitHub PR/CI；服务当前未启动。

Preserve:

- 不修改或提交 `.idea`、deployment、Docker、Hook、脚本及其他未纳入当前阶段的既有工作树改动；混合修改文件保持原样。
- `agent-fronted` 是当前唯一前端目录；历史旧 Item 和旧数据库结构只用于迁移或只读展示。
