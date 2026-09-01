status: active
updated: 2026-09-01

# Task Handoff

Goal:

- 完成 Week 4 `codex/demo-acceptance`：把本地验收 runner、订单夹具幂等边界、浏览器矩阵记录和四周交接文档收口到可审阅状态。
- 保持 Commerce Guardian Agent 的 Thread → Turn → Item、确定性 Workflow、持久化事实和现有 HTTP/SSE/V9 契约；本阶段不部署、不生产化、不替换 GitHub `master`。

Completed:

- `scripts.acceptance` 已扩展为检查 Item 游标严格前进、刷新恢复、开放交互唯一性、Turn `clientRequestId` 幂等和执行轨迹回放；可选运行物流、退款幂等、催发货临时失败重试和删除场景。
- 删除动作默认 gated；只有显式 `--allow-destructive-fixture-actions` 才会访问一次性夹具订单 `ORDER-EXT-DELIVERED-001`，并验证重放及订单/物流 404 清理。
- `scripts/tests/test_acceptance.py` 覆盖无开放交互重读、交互替换拒绝、游标错误、订单动作重放和删除开关；独立 SQLite 订单夹具 HTTP 验收已通过物流、退款、催发货重试和显式删除，最终统计为幂等记录 3、业务变更 3、注入失败 3。
- README、架构、现场复核手册和追踪矩阵已补充 Week 4 runner 用法、浏览器四尺寸记录项、外部证据边界和敏感信息约束。
- 从 `codex/agent-quality-eval@6c7e82f` 重跑确定性评测 12 场景 × 3 次：安全不变量 36/36，路由与终止 36/36；该 runner 不连接真实模型。
- `codex/demo-acceptance` 已推送到 GitHub 并复核远端引用；未更新集成分支、`master` 或 GitCode upstream。

Decisions:

- 本分支只包含 Week 4 验收闭环；Week 1～3 PR 仍按顺序以 `codex/commerce-guardian-agent` 为目标，不在本分支合并或改写 `master`。
- 真实模型结果只进入本机脱敏报告，不进入 CI；浏览器、数据库副本和第三方订单服务证据必须在对应环境实际执行，不能由组件测试或确定性 runner 推断。
- 用户已授权本计划内明确的一次性 SQLite 夹具可直接执行删除并在运行结束清理；该授权不扩展到正式数据库、共享服务或非夹具订单。默认开关仍保持关闭，便于误连时安全失败。
- 原工作区已有 `.idea`、deployment、Docker、Hook、脚本和其他 dirty 资产不从隔离工作树导入；本阶段不 force push、不改变 GitCode upstream。

TODO:

- 在具备 Agent、前端和订单夹具进程后执行完整 `python -m scripts.acceptance ...`；可按本次授权在一次性夹具中启用删除开关。
- 在可用的真实模型凭据下运行 12 场景 × 3 次，生成忽略提交的脱敏 JSON/Markdown，并与 36/36 确定性基线比较。
- 运行 V7→V8→V9 一次性数据库副本，保留历史业务事实和记录数校验；完成四个视口、深浅主题、键盘/Esc、reduced-motion、SSE 重连和刷新恢复浏览器记录。
- 重新执行完整 Python/Maven/前端门禁；待 Week 1 PR #2 移除集成基线废弃 Docker 编排后，重跑 Python convention 和 GitHub CI。
- 创建 Week 4 PR；目标保持 `codex/commerce-guardian-agent`，不自动合并或关闭。

Blocked:

- 当前环境没有 `DEEPSEEK_API_KEY` 或真实 Agent/前端服务，因此真实模型 36 次、SSE/刷新浏览器验收和数据库副本迁移尚未执行。
- 本分支从尚未合并 Week 1 PR 的集成基线创建，`python -m scripts.convention_check` 被基线 `docker-compose.yml` 的两个既有禁用字符串（遗留内存配置名和旧仓库名）阻塞；不修改该 Docker 资产，待 PR #2 合并序列重跑。

Next action:

- 创建 Week 4 PR；外部环境可用后按本 handoff 的 TODO 顺序补齐证据。

Validation:

- `D:\Application\miniconda3\python.exe -m unittest scripts.tests.test_acceptance`：5 项通过。
- 独立 SQLite 订单夹具完整验收：`logistics`、`refund-idempotency`、`expedite-retry`、`delete-idempotency` 通过；统计 `idempotencyRecords=3`、`businessMutations=3`、`injectedFailures=3`，临时目录随进程结束清理。
- `codex/agent-quality-eval@6c7e82f` 的 `python -m scripts.runtime_eval --repetitions 3`：安全 36/36，路由 36/36。
- 当前完整脚本发现集：13 项中因上述两个基线 Docker 文本问题失败 1 项；Maven、前端完整门禁待本阶段代码定稿后运行。

Preserve:

- 不修改或提交原工作区既有 dirty 资产；禁止把 `.idea`、deployment、Docker、Hook、未授权脚本和配置混入本阶段提交。
- 不保存 Prompt、Thinking、API key、完整敏感响应或真实模型原文；`output/runtime_eval` 等报告目录保持忽略。
- 保留 PR #1～#4、`codex/commerce-review-base` 和所有既有远端分支；不合并、关闭或删除它们。
