status: active
updated: 2026-09-01

# Task Handoff

Goal:

- 完成第三周显式 Agent 决策契约：普通消息 Turn 必须以受控 `FINISH`、持久化 QuestionCard 或确定性 Workflow 收口；缺失决策安全失败并允许用户显式重试。
- 保持 `/api/agent`、SSE envelope、Thread→Turn→Item 和 V9 数据库结构不变；本分支只覆盖第三周契约，不合并或修改原工作区改动。

Completed:

- `complete_agent_cycle` 仅接受 `FINISH`；`ASK_USER` 只能由 `request_user_input` 持久化 QuestionCard 产生，`START_WORKFLOW` 只能由 Workflow Tool 产生。
- `SpringAiAgentTurnCoordinator` 在终止 Tool 后只采用受控消息，忽略模型追加自由文本；同一调用链重复 Workflow/QuestionCard Tool 时复用已有事实，不创建第二个 Run 或问题卡。
- Runtime 对没有终止决策且没有结构化副作用的首次结果最多执行一次纠正调用；第二次仍缺失时写入 `AGENT_DECISION_MISSING`、失败收口且不持久化自由文本。
- 前端投影新增错误码和纠正标志；只有 `AGENT_DECISION_MISSING` 显示“再次尝试”，并以新 Turn/requestId 调用现有提交 API。
- 文档已同步架构、运行手册、README、实现追踪矩阵和 `docs/agent-decision-contract.md`。
- 第 1 周 PR #2 已合入集成（`4742e253`），第 2 周 PR #3 已合入集成（`2ee1f062`）；`master` 仍为 `2106978`，GitCode upstream 未改动。
- 原工作区的 `.idea`、deployment、Docker、Hook、脚本和其他未提交改动保持原样，未暂存、未提交、未带入本分支。

Decisions:

- `AgentCoordinatorResult` 以可选 `correctionAttempt` 标记纠正调用，旧构造边界保持兼容。
- 已存在 WorkflowRun、QuestionCard、Checkpoint 或结构化副作用时不自动再次调用模型，避免重复外部写操作或孤立等待。
- 每周一个前后端闭环垂直切片，周 PR 只面向 `codex/commerce-guardian-agent`；不更新 GitHub `master`，不 force push，不改变 GitCode upstream。
- 真实模型只在本机执行，不进入稳定 CI；外部写操作继续由持久化 Workflow、Checkpoint 和 ExternalActionCommand 控制。

TODO:

- 推送已合入第 2 周基线的 `codex/agent-decision-contract`，更新 PR #4，等待 GitHub 确定性 CI/Codex 审查结果后按顺序合入。
- 在真实模型和浏览器黄金路径中复核终止消息、`AGENT_DECISION_MISSING` 重试和刷新恢复；将结果补入追踪矩阵。
- PR #4 合入后创建/更新第四周演示验收分支，并继续按用户授权处理 PR #5；不合并 GitHub `master`。

Blocked:

- 当前无代码或外部权限阻塞；PR #4 需先完成本地基线合并、推送并等待 GitHub 检查。
- 真实模型/浏览器尚未在本次分支重跑，属于阶段验收项，不视为第三周实现失败。

Next action:

- 检查并提交本次集成基线合并，运行 Maven、Python、前端及定向决策契约门禁，随后推送并观察 PR #4。

Validation:

- 第三周原分支已通过：`mvn clean '-DskipTests=false' test`（Core 53、Infrastructure 80、App 19）；`mvn verify`（HTTP `*IT` 9 项）；`mvn dependency:analyze -DskipTests`；前端 typecheck、Vitest 49 项、组件测试 25 项、production build；`git diff --check`。
- 合入第 1、2 周基线后需重跑 Python convention、全量脚本测试、Maven、前端和确定性 12 场景评测；不连接真实模型、数据库或订单服务。
- PR #4 当前 head 为 `e4037c3`，base 为 `codex/commerce-guardian-agent`；更新后等待 GitHub/Codex 结果。

Preserve:

- 不修改或提交原工作区 `.idea`、deployment、Docker、Hook、脚本及其他未纳入本分支的既有改动。
- `agent-fronted` 是当前唯一前端目录；历史 Item/数据库结构只做迁移或只读兼容。
- `AGENTS.md` 是完整长期规范；本 handoff 只保留最新状态快照，不累积过程日志。
