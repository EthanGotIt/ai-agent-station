status: active
updated: 2026-08-30

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
- Week 1 PR #2 已创建并保持 Open/非 Draft，base=`codex/commerce-guardian-agent`、head=`codex/demo-baseline`；创建时 2 项检查通过、Python 规范门禁失败，未合并或关闭。
- `a851b40` 已在 Week 1 分支移除废弃的 Docker 编排文件，`7e0745b` 修正文档禁用文本；本地规范检查与脚本 9 项测试通过，GitHub Actions `deterministic-ci #8`（push）和 `#9`（pull_request）全部成功。
- Week 1 PR #2 的 Codex 自动审查未发现 P0/P1，另有一条 P2 建议要求 `git diff --check` 使用显式 base/head 范围；该建议尚未处理，不阻塞本周门禁。

Decisions:

- 后续周 PR 只合入 `codex/commerce-guardian-agent`；不更新 GitHub `master`，不 force push，不改变 GitCode upstream。
- 每周一个前后端闭环垂直切片；真实模型只用于本机质量报告，不进入稳定 CI；外部写操作继续由 Workflow、Checkpoint 和 ExternalActionCommand 控制。
- 不导入原工作区的 dirty Docker/deployment/脚本修改；旧 Docker 配置已在 Week 1 分支用独立提交移除，原工作区仍保持不变。
- 临时迁移脚本不提交；迁移克隆库保留在本机供复核，源库不改写。

TODO:

- 按运行手册补跑真实模型和四尺寸浏览器黄金路径；完成后再进入第 2 周 `codex/agent-quality-eval`，然后依次推进决策契约和演示验收。
- 评估并在需要时单独修正 Codex 提出的 CI whitespace P2；不得让它覆盖业务门禁或引入无关改动。
- 第 2～4 周的场景格式、确定性 runner、`AGENT_DECISION_MISSING` 收口和验收文档尚未实现。

Blocked:

- 真实模型请求和当前环境的浏览器黄金路径尚未重跑。

Next action:

- 先按运行手册补跑真实模型和浏览器黄金路径，再决定是否处理 whitespace P2；PR #2 保持 Open，不合并。

Validation:

- 干净克隆：`git diff --check`、对象/秘密扫描通过；移除废弃 Docker 编排文件并修正文档后，Python convention 与脚本测试 9 项本地通过。
- Maven：Core 50、Infrastructure 77、App 19 的 `clean test` 通过；`verify` 的真实 HTTP `*IT` 9 项通过；依赖分析无问题。
- 前端：`npm ci`、typecheck、Vitest、组件测试和 production build 通过。
- 数据库：V7→V8→V9 临时克隆重放通过；旧业务事实、校验和与 V9 前 Thread/Turn 投影未改写，V8 图快照未回填。
- GitHub：17 个分支和审查基线已推送；PR #1 Codex 自动审查为 👍、无 P0/P1；Week 1 PR #2 已创建；`deterministic-ci #8`/`#9` 的 Python、Maven、前端检查全部成功。

Preserve:

- 原工作区全部既有未提交改动必须保持原样；严禁 `git add -A` 或把 `.idea`、deployment、Docker、Hook、脚本和配置混入本阶段提交。
- `AGENTS.md` 是完整长期规范；本 handoff 只保留最新状态快照，不累积过程日志。
