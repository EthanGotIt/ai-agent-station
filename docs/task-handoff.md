status: active
updated: 2026-09-01

# Task Handoff

Goal:

- 完成第三周显式 Agent 决策契约：普通消息 Turn 必须以受控 `FINISH`、持久化 QuestionCard 或确定性 Workflow 收口；缺失决策安全失败并允许用户显式重试。
- 保持 `/api/agent`、SSE envelope、Thread→Turn→Item 和 V9 数据库结构不变；本分支只覆盖第三周契约，不合并或修改用户原工作区改动。

Completed:

- `complete_agent_cycle` 仅接受 `FINISH`；`ASK_USER` 只能由 `request_user_input` 持久化 QuestionCard 产生，`START_WORKFLOW` 只能由 Workflow Tool 产生。
- `SpringAiAgentTurnCoordinator` 在终止 Tool 后只采用受控消息，忽略模型追加自由文本；同一调用链重复 Workflow/QuestionCard Tool 时复用已有事实，不创建第二个 Run 或问题卡。
- Runtime 对没有终止决策且没有结构化副作用的首次结果最多执行一次纠正调用；第二次仍缺失时写入 `AGENT_DECISION_MISSING`、失败收口且不持久化自由文本。
- 前端投影新增错误码和纠正标志；只有 `AGENT_DECISION_MISSING` 显示“再次尝试”，并以新 Turn/requestId 调用现有提交 API。
- 文档已同步架构、运行手册、README、实现追踪矩阵和 `docs/agent-decision-contract.md`。

Decisions:

- `AgentCoordinatorResult` 以可选 `correctionAttempt` 标记纠正调用，旧构造边界保持兼容。
- 已存在 WorkflowRun、QuestionCard、Checkpoint 或结构化副作用时不自动再次调用模型，避免重复外部写操作或孤立等待。
- 真实模型、浏览器和 GitHub PR 属于外部验收；本轮不创建 PR，待用户明确确认后再操作。

TODO:

- 用户确认后创建 Week3 PR（base `codex/commerce-guardian-agent`，head `codex/agent-decision-contract`）；不触碰 GitHub `master`。
- 在真实模型和浏览器黄金路径中复核终止消息、`AGENT_DECISION_MISSING` 重试和刷新恢复；将结果补入追踪矩阵。
- 通过后再进入第四周演示验收分支；不合并 GitHub `master`。

Blocked:

- Python 规范门禁仍被基线 `docker-compose.yml` 的两处旧标识阻塞（`ai-agent-station`、`AGENT_MEMORY`）；该 Docker 文件按计划视为用户资产，本分支未修改或提交。
- 真实模型/浏览器/PR 尚未执行；PR 创建仍等待用户当前轮确认，属于外部验收前置条件，不视为第三周实现失败。

Next action:

- 等待用户确认后创建 Week3 PR；Python 基线阻塞保持显式记录。

Validation:

- 已通过：`mvn clean '-DskipTests=false' test`（Core 53、Infrastructure 80、App 19，0 失败）；`mvn verify`（HTTP `*IT` 9 项，0 失败）；`mvn dependency:analyze -DskipTests`；前端 typecheck、Vitest 49 项、组件测试 25 项、production build；`git diff --check`。
- Python：Miniconda `convention_check` 发现 `docker-compose.yml` 两处基线旧标识，脚本单测 9 项中 1 项随之失败；未修改 Docker 用户资产。
- GitHub：`codex/agent-decision-contract` 已推送，远端 SHA `9bca7f572519a551d752f41fddb1615fb4a9f3a6`；PR 尚未创建。
- 未执行：真实模型、浏览器交互和 GitHub PR/CI；服务当前未启动。

Preserve:

- 不修改或提交原工作区 `.idea`、deployment、Docker、Hook、脚本及其他未纳入本分支的既有改动。
- `agent-fronted` 是当前唯一前端目录；历史 Item/数据库结构只做迁移或只读兼容。
