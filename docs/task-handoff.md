status: active
updated: 2026-09-01

# Task Handoff

Goal:

- 完成 Week 4 `codex/demo-acceptance`：把本地验收 runner、订单夹具幂等边界、浏览器矩阵记录和四周交接文档收口到可审阅状态。
- 保持 Commerce Guardian Agent 的 Thread → Turn → Item、确定性 Workflow、持久化事实和现有 HTTP/SSE/V9 契约；本阶段不部署、不生产化、不替换 GitHub `master`。

Completed:

- `scripts.acceptance` 已扩展为检查 Item 游标严格前进、刷新恢复、开放交互唯一性、Turn `clientRequestId` 幂等和执行轨迹回放；可选运行物流、退款幂等、催发货临时失败重试和删除场景。
- 用户已确认一次性夹具订单可直接删除；`ORDER-EXT-DELIVERED-001` 的物流、退款幂等、催发货重试和删除幂等均已通过，最终统计为幂等记录 3、业务变更 3、注入失败 3，临时目录随运行结束清理。
- `scripts/tests/test_acceptance.py` 的 5 项测试覆盖无开放交互重读、交互替换拒绝、游标错误、订单动作重放和删除开关。
- README、架构、现场复核手册和追踪矩阵已补充 Week 4 runner 用法、浏览器四尺寸记录项、外部证据边界和敏感信息约束。
- 第 2 周确定性评测 12 场景 × 3 次保持安全 36/36、路由与终止 36/36；该 runner 不连接真实模型。
- 第 1 周 PR #2、Week 2 PR #3、Week 3 PR #4 已按顺序合入 `codex/commerce-guardian-agent`，合并提交分别为 `4742e253`、`2ee1f062`、`1ef78fc`；`master` 仍为 `2106978`，GitCode upstream 未改动。
- 原工作区已有 `.idea`、deployment、Docker、Hook、脚本和其他 dirty 资产未从隔离工作树导入；未 force push。

Decisions:

- 本分支只包含 Week 4 验收闭环；周 PR 只面向 `codex/commerce-guardian-agent`，不合并或改写 `master`。
- 真实模型结果只进入本机脱敏报告，不进入 CI；浏览器、数据库副本和第三方订单服务证据必须在对应环境实际执行，不能由组件测试或确定性 runner 推断。
- 一次性 SQLite 夹具删除已获用户授权；该授权不扩展到正式数据库、共享服务或非夹具订单，默认开关仍保持关闭。
- 所有外部写操作继续由持久化 Workflow、Checkpoint 和 ExternalActionCommand 控制；不新增部署、生产化或其他业务能力。

TODO:

- 重新执行合入 #2→#4 基线后的完整 Python/Maven/前端门禁，并更新本 handoff 的验证数字。
- 在具备 Agent、前端和订单夹具进程后执行完整 `python -m scripts.acceptance ...`；一次性夹具删除可按既有授权执行并清理。
- 在可用的真实模型凭据下运行 12 场景 × 3 次，生成忽略提交的脱敏 JSON/Markdown，并与 36/36 确定性基线比较。
- 完成四个视口、深浅主题、键盘/Esc、reduced-motion、SSE 重连和刷新恢复浏览器记录；真实 Agent 黄金路径仍需现场复核。
- 更新 PR #5 后等待 GitHub 检查/Codex 审查结果，再按用户已授权顺序合入；保留 PR #1、审查基线和所有远端分支。

Blocked:

- 当前无代码或 GitHub 权限阻塞；真实模型、浏览器现场和生产数据库不在当前自动化环境中，属于 Week 4 外部验收项。

Next action:

- 检查并提交已解决的文档冲突，运行 Week4 全量门禁和一次性夹具验收，推送 `codex/demo-acceptance` 并观察 PR #5；检查全部成功后合入。

Validation:

- 合并前已通过：`python -m unittest scripts.tests.test_acceptance` 5 项；独立 SQLite 夹具 `logistics`、`refund-idempotency`、`expedite-retry`、`delete-idempotency`；确定性评测安全 36/36、路由 36/36。
- 合入 #2→#4 后需重跑：`python -m scripts.convention_check`、`python -m unittest discover -s scripts/tests -p "test_*.py"`、Maven `clean test`/`verify`/依赖分析，以及前端 typecheck/Vitest/组件测试/build。
- PR #5 当前 head 为 `6f00516`，base 为 `codex/commerce-guardian-agent`；更新后等待新 head 的 GitHub workflow。

Preserve:

- 不修改或提交原工作区既有 dirty 资产；禁止把 `.idea`、deployment、Docker、Hook、未授权脚本和配置混入本阶段提交。
- 不保存 Prompt、Thinking、API key、完整敏感响应或真实模型原文；`output/runtime_eval` 等报告目录保持忽略。
- `AGENTS.md` 是完整长期规范；本 handoff 只保留最新状态快照，不累积过程日志。
