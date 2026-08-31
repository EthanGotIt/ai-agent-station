status: active
updated: 2026-08-31

# Task Handoff

Goal:

- 完成 GitHub 发布、Codex 自动审查和四周持续迭代；当前集成分支为 `codex/commerce-guardian-agent`。
- 第 2 周建立可重复的 Agent 质量评测基线，保持 Thread → Turn → Item、确定性 Workflow、持久化事实和现有 HTTP/SSE 契约不变。

Completed:

- 原工作区的 `.idea`、deployment、Docker、Hook、脚本和其他未提交改动保持原样，未暂存、未提交、未带入推送。
- 既有 17 个 `codex/*` 分支、审查基线和 Week 1 基线已推送到 GitHub；`master` 未改变；PR #1 的 Codex 自动审查获得 👍 且无 P0/P1，PR #2 保持 Open/非 Draft。
- Week 2 已在隔离克隆中从 `codex/commerce-guardian-agent` 创建 `codex/agent-quality-eval`，审阅并吸收原工作区未提交的 `scripts/runtime_eval`，没有覆盖原工作区。
- 新增内部 `EvalScenario` 格式和 12 个固定场景：精确订单、今日订单、停滞物流、物流详情、退款缺订单、退款缺原因、退款拒绝/批准、催发货失败/人工重试、删除拒绝/批准。
- 确定性 runner 默认执行 12 场景 × 3 次；本地结果为安全边界 36/36、路由与终止 36/36。新增 GitHub Actions 只运行该 runner 与定向单测，不连接真实模型、数据库或订单服务。
- 本机 live runner 仅接收已脱敏结构化观察结果，拒绝 Prompt、Thinking、原始响应、密钥和请求头字段，JSON/Markdown 摘要写入被忽略的 `output/runtime_eval/`。

Decisions:

- 每周一个前后端闭环垂直切片，周 PR 只面向 `codex/commerce-guardian-agent`；不更新 `master`，不 force push，不改变 GitCode upstream。
- 真实模型 runner 只在本机执行，不进入稳定 CI；确定性评测是 CI 门禁，报告不保存原始模型内容。
- Week 2 使用独立工作树吸收未提交评测代码；原工作区继续保留用户资产，不通过复制或覆盖改变其状态。
- 外部写操作继续由持久化 Workflow、Checkpoint 和 ExternalActionCommand 控制；本周只验证评测不变量，不新增业务动作。

TODO:

- 提交并推送 `codex/agent-quality-eval`，观察确定性评测 workflow 后再请求创建 Week 2 PR；创建 PR 前取得用户确认。
- 真实模型 runner 的 12 场景 × 3 次仅在本机执行，输出脱敏 JSON/Markdown 汇总，并记录安全 36/36、路由至少 35/36 的结果。
- 第 3 周创建 `codex/agent-decision-contract`，收口显式终止决策和 `AGENT_DECISION_MISSING` 安全失败；第 4 周再做演示验收和稳定化。

Blocked:

- 当前仅有本机确定性结果；真实模型与浏览器黄金路径尚未在本周工作树重跑。
- Week 2 PR 尚未创建，等待本地提交/推送验证和动作前确认。

Next action:

- 先完成本分支的静态检查、定向单测、提交和 GitHub Actions 验证，再请求创建 Week 2 PR；不合并。

Validation:

- `python -m scripts.runtime_eval --repetitions 3`：安全 36/36，路由与终止 36/36。
- `python -m unittest scripts.tests.test_runtime_eval scripts.tests.test_runtime_eval_scenarios scripts.tests.test_live_runner`：6 项通过。
- 报告文件只包含结构化场景摘要；`output/runtime_eval/` 未被 Git 跟踪。
- 原集成分支的全量旧脚本规范测试仍受未合并 Week 1 Docker 清理影响，本周 workflow 不重复该门禁；Week 1 PR 的全量确定性 CI 已通过。

Preserve:

- 原工作区全部既有未提交改动必须保持原样；严禁 `git add -A` 或把 `.idea`、deployment、Docker、Hook、脚本和配置混入本分支提交。
- `AGENTS.md` 是完整长期规范；本 handoff 只保留最新状态快照，不累积过程日志。
