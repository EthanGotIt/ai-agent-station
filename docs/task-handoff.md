status: active
updated: 2026-09-01

# Task Handoff

Goal:

- 完成 GitHub 发布、Codex 自动审查和四周持续迭代；当前集成分支为 `codex/commerce-guardian-agent`。
- 第 2 周建立可重复的 Agent 质量评测基线，保持 Thread → Turn → Item、确定性 Workflow、持久化事实和现有 HTTP/SSE 契约不变。

Completed:

- 原工作区的 `.idea`、deployment、Docker、Hook、脚本和其他未提交改动保持原样，未暂存、未提交、未带入推送。
- 既有 17 个 `codex/*` 分支、审查基线和 Week 1 基线已推送到 GitHub；`master` 仍为 `2106978`；GitCode upstream 未改动。
- GitHub PR #1 保持 Open/非 Draft，base=`codex/commerce-review-base`、head=`codex/commerce-guardian-agent`；Codex 自动审查获得 👍 且无 P0/P1，未触发 Security Review。
- Week 1 PR #2 已按顺序合入 `codex/commerce-guardian-agent`，合并提交为 `4742e25380f8233f177e279f768e19d5a55094af`；未改变 `master`。
- Week 1 的确定性 CI、三条 Code Review Rules、Docker 历史清理和文档禁用文本修正均已随 PR #2 进入集成基线，相关 push/pull_request workflow 检查通过。
- Week 2 已在隔离克隆中创建 `codex/agent-quality-eval`，审阅并吸收原工作区未提交的 `scripts/runtime_eval`，没有覆盖原工作区；当前正在把 PR #2 的集成基线合入该分支。
- 新增内部 `EvalScenario` 格式和 12 个固定场景：精确订单、今日订单、停滞物流、物流详情、退款缺订单、退款缺原因、退款拒绝/批准、催发货失败/人工重试、删除拒绝/批准。
- 确定性 runner 默认执行 12 场景 × 3 次；此前本地结果为安全边界 36/36、路由与终止 36/36。真实模型 runner 仅在本机运行，报告只保留脱敏结构化摘要。

Decisions:

- 每周一个前后端闭环垂直切片，周 PR 只面向 `codex/commerce-guardian-agent`；不更新 GitHub `master`，不 force push，不改变 GitCode upstream。
- 真实模型 runner 只在本机执行，不进入稳定 CI；确定性评测是 CI 门禁，报告不保存原始模型内容、Thinking、密钥或完整敏感响应。
- Week 2 使用独立工作树吸收评测代码；原工作区继续保留用户资产，不通过复制或覆盖改变其状态。
- 外部写操作继续由持久化 Workflow、Checkpoint 和 ExternalActionCommand 控制；本阶段只验证评测不变量，不新增业务动作。

TODO:

- 完成当前 Week 2 分支的文档冲突收口、合并提交、静态检查和确定性评测，推送 `codex/agent-quality-eval` 并更新 PR #3。
- 等待 PR #3 的确定性 CI/评测检查通过后，按用户授权合入集成分支；随后依次更新并合入 PR #4、PR #5。
- 真实模型 runner 的 12 场景 × 3 次仍只在本机执行，输出脱敏 JSON/Markdown 汇总，并记录安全 36/36、路由至少 35/36 的结果。

Blocked:

- 当前无外部阻塞；PR #3 需要先完成本地基线合并并等待 GitHub 检查。

Next action:

- 暂存并检查已解决的两个文档冲突，创建 Week 2 基线合并提交；随后运行 Python convention、脚本测试和 runtime eval，再推送并观察 PR #3。

Validation:

- `python -m scripts.runtime_eval --repetitions 3`：此前安全 36/36，路由与终止 36/36；合入 Week 1 基线后需重跑确认。
- `python -m unittest discover -s scripts/tests -p "test_*.py"`：Week 2 目标为全量通过。
- 报告文件只包含结构化场景摘要；`output/runtime_eval/` 未被 Git 跟踪。
- Week 1 GitHub `deterministic-ci` push/pull_request 检查全部成功；PR #2 已合入后，Week 2 分支需重新等待基线检查。

Preserve:

- 原工作区全部既有未提交改动必须保持原样；严禁 `git add -A` 或把 `.idea`、deployment、Docker、Hook、脚本和配置混入本阶段提交。
- `AGENTS.md` 是完整长期规范；本 handoff 只保留最新状态快照，不累积过程日志。
