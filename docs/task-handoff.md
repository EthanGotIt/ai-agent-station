status: active
updated: 2026-08-29

# Task Handoff

Goal:

- 完成 GitHub 发布、Codex 自动审查和四周持续迭代的可恢复交接；当前集成分支为 `codex/commerce-guardian-agent`。
- 保持 Commerce Guardian Agent 的 Thread → Turn → Item、确定性 Workflow、持久化事实和现有 HTTP/SSE 契约，不把演示项目扩展为部署或生产化项目。

Completed:

- 原工作区的 `.idea`、deployment、Docker、Hook、脚本和其他未提交改动保持原样，未暂存、未提交、未带入推送。
- 已将现有 17 个 `codex/*` 分支显式推送到 GitHub；`master` 仍为 `2106978`，GitCode upstream 未改动；已创建并推送审查基线 `codex/commerce-review-base` 指向 `d660f64`。
- GitHub PR #1 已创建且保持 Open/非 Draft：base=`codex/commerce-review-base`、head=`codex/commerce-guardian-agent`，标题为 `fix: harden workflow result projection and replay`，覆盖 6 个提交/17 个文件。Codex 自动审查已触发并给出 👍，未发现 P0/P1；未合并、未关闭、未删除基线、未触发 Security Review。
- 在干净克隆中完成对象/秘密扫描、`git diff --check`、Maven `clean test`/`verify`/依赖分析和前端 `npm ci`、typecheck、Vitest、组件测试、production build；Maven 与前端全部通过。
- 在只读源库 `COMMERCE_GUARDIAN_AGENT` 的一次性克隆 `COMMERCE_GUARDIAN_AGENT_MIGRATION_20260829` 中回退 V7 形态并重放 V8、V9；历史业务表计数/校验和和 Thread/Turn 的 V9 前投影保持不变，V8 未回填历史图快照。
- `e4e8dd6` 已在 `codex/demo-baseline` 增加确定性 GitHub Actions 和三条 Code Review Rules 并推送；Actions `deterministic-ci #1` 的 Maven、前端通过，Python 门禁因已提交旧 `docker-compose.yml` 的两个禁用字符串失败。

Decisions:

- 后续周 PR 只合入 `codex/commerce-guardian-agent`；不更新 GitHub `master`，不 force push，不改变 GitCode upstream。
- 每周一个前后端闭环垂直切片；真实模型只用于本机质量报告，不进入稳定 CI；外部写操作继续由 Workflow、Checkpoint 和 ExternalActionCommand 控制。
- 不导入原工作区的 dirty Docker/deployment/脚本修改来“修复”周分支；CI 失败原因必须以独立提交和可审阅证据处理。
- 临时迁移脚本不提交；迁移克隆库保留在本机供复核，源库不改写。

TODO:

- 等待用户对创建第 1 周 PR 的动作确认；确认后创建 base=`codex/commerce-guardian-agent`、head=`codex/demo-baseline` 的非 Draft PR，不合并。
- 处理并重新验证 Python CI 的旧 `docker-compose.yml` 规范门禁阻塞，同时保留原工作区 Docker 删除改动不变。
- 按运行手册补跑真实模型和四尺寸浏览器黄金路径；完成后再进入第 2 周 `codex/agent-quality-eval`，然后依次推进决策契约和演示验收。
- 第 2～4 周的场景格式、确定性 runner、`AGENT_DECISION_MISSING` 收口和验收文档尚未实现。

Blocked:

- `deterministic-ci #1` 的 Python job 仍因 committed `docker-compose.yml` 中 `AGENT_MEMORY` 与旧项目名禁用文本失败；不能用跳过检查或引入 dirty worktree 文件掩盖。
- 真实模型请求和当前环境的浏览器黄金路径尚未重跑。
- 第 1 周 PR 创建属于新的 GitHub 代表性外部操作，等待用户在动作前确认。

Next action:

- 用户确认后创建 Week 1 PR；随后先处理 Python CI 阻塞并重跑 Actions，保持 PR/审查记录可追踪。

Validation:

- 干净克隆：`git diff --check`、对象/秘密扫描通过；Python convention 发现 2 个旧 `docker-compose.yml` 禁用字符串，脚本测试 9 项中 1 项因此失败。
- Maven：Core 50、Infrastructure 77、App 19 的 `clean test` 通过；`verify` 的真实 HTTP `*IT` 9 项通过；依赖分析无问题。
- 前端：`npm ci`、typecheck、Vitest、组件测试和 production build 通过。
- 数据库：V7→V8→V9 临时克隆重放通过；旧业务事实、校验和与 V9 前 Thread/Turn 投影未改写，V8 图快照未回填。
- GitHub：17 个分支和审查基线已推送；PR #1 Codex 自动审查为 👍、无 P0/P1；`deterministic-ci #1` Maven/前端成功、Python 失败。

Preserve:

- 原工作区全部既有未提交改动必须保持原样；严禁 `git add -A` 或把 `.idea`、deployment、Docker、Hook、脚本和配置混入本阶段提交。
- `AGENTS.md` 是完整长期规范；本 handoff 只保留最新状态快照，不累积过程日志。
