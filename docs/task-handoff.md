status: active
updated: 2026-09-01

# Task Handoff

Goal:

- 将 `codex/commerce-guardian-agent` 作为 GitHub 默认主力分支，持续累积可独立验证的小提交。
- 本轮通过 `codex/integration-sync-20260901` 汇总仍有价值的 CI、运行时、npm fallback、部署清理和已授权本地项目设置；`master` 继续保留里程碑版本。

Completed:

- 已从主力分支 `38b971b` 创建同步分支，并保留以下独立阶段提交：工作流 `ci`/`eval` 命名、Python 3.14/Node 24/JDK 17 对齐、npm registry fallback、deployment/Docker 打包清理、分支治理、已授权 `.idea` 项目设置和 hook/根 Docker 忽略文件移除。
- 已推送 `codex/integration-sync-20260901`，并创建 GitHub PR #8（base=`codex/commerce-guardian-agent`，非 Draft）。
- GitHub 默认分支已是 `codex/commerce-guardian-agent`；`master` 保持 `93ff0b3`，未合并、未改写。
- `ChatGPT Codex Connector` 已连接 EthanGotIt，目标仓库的 Codex 云端环境存在；PR #8 的自动 Codex Review 已返回建议。
- 订单服务夹具继续作为独立 Python 进程承担本地 HTTP 验收，已移除 Docker 打包和 deployment/CD 资产。

Decisions:

- 不强推、不删除远端分支、不把 `master` 反向合入主力；`archive/*` 分支保留，不回流旧架构。
- `.idea`、`.githooks`、`.dockerignore` 的当前用户授权内容已纳入同步 PR；根工作区原有 dirty 状态仍不直接操作。
- 不配置 Codex 密钥、部署脚本或生产化能力；真实模型和浏览器验收仍只作为本地门禁。

TODO:

- 推送本轮 Review 修正提交，确认 P0/P1 已收口，再以普通 merge commit 合入 PR #8。
- 合并后刷新本 handoff 为完成状态，并核对主力分支、`master` SHA、归档分支和本地 stash。
- 在确认分支规则方案后，为主力和 `master` 配置不允许 force push/删除的 GitHub ruleset。

Blocked:

- 当前没有代码或推送权限阻塞；GitHub Codex P1 是对提交分阶段的审查意见，需以实际提交记录回复核对；Docker 引擎不可用不再是阻塞，因为 Docker 打包已移除。

Next action:

- 完成本地 Review 修正的最小测试，按明确路径提交并推送同步分支，随后重新检查 PR #8。

Validation:

- Python 3.14 `convention_check`、脚本测试 19 项和确定性 runtime eval：安全边界 36/36，路由与终止 36/36。
- npm fallback 测试、官方源优先安装、前端 typecheck、Vitest 49/49、组件测试 25/25、production build 通过。
- Maven clean test：Core 54、Infrastructure 82、App 19；`verify` 集成测试 9 项；dependency analyze 无问题。
- `git diff --check` 和 Git 对象连通性检查通过；未运行 Docker 构建，因本 PR 已删除 Docker 打包。

Preserve:

- 根工作区 `.idea`、deployment、Docker、Hook 及其他用户资产的原始 dirty 状态，不在同步工作树之外擅自覆盖。
- 本地 `stash@{0}`（对象 `a086787`）及历史 WIP stash 保留，直到用户确认不再需要。
- 不保存 Prompt、Thinking、API key、完整敏感响应或真实模型原文；`output/runtime_eval` 等报告目录保持忽略。
- `AGENTS.md` 是完整长期规范；本 handoff 只保留当前恢复所需事实。
