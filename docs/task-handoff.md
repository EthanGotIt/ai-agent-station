status: active
updated: 2026-09-01

# Task Handoff

Goal:

- 以 `codex/commerce-guardian-agent` 作为最新稳定集成分支，持续累积可独立验证的小提交。
- 需要合入 GitHub `master` 时，再从该稳定分支创建临时 promotion 分支、提交并创建 PR；不在稳定分支上直接创建 promotion 分支。

Completed:

- 本地稳定分支已从 `422403d` 安全快进到远端 Week 4 合并提交 `cf436605`，未使用 force push。
- 已直接在 `codex/commerce-guardian-agent` 提交并推送两个小提交：`eb7e6e5`（消息请求幂等、外部动作事实核验、批准后订单事实缺失安全收口及运行时配置）和 `b6415e9`（评审进程命令身份校验、前端目录忽略规则）。
- GitHub 同名分支当前为 `b6415e9`；`master` 保持 `93ff0b3`，未合并、未改写。
- 后端 `mvn -B -DskipTests=false clean test` 通过：Core 54、Infrastructure 82、App 19；Python convention 与脚本 19 项通过；确定性评测安全边界和路由终止均为 36/36；前端 typecheck、Vitest 49/49 和 production build 通过。
- 对应工作区原始 WIP 已保存在本地命名 stash `a086787`；`.idea`、deployment、Docker、Hook 等用户资产仍保持未提交 dirty 状态，没有带入上述提交。

Decisions:

- 当前不创建 `codex/local-runtime-hardening`，也不为本轮直接推送创建新 PR；现有同名分支作为稳定进度来源。
- 后续只做聚焦的小提交并推送 `codex/commerce-guardian-agent`；准备合入 `master` 时才创建 promotion 分支和 PR。
- 不强推、不删除远端分支或审查基线；真实模型、部署和生产化继续不纳入本阶段。

TODO:

- 在稳定分支上继续按垂直切片累积后端契约、前端投影、测试和文档的小提交。
- 用户确认需要发布到 `master` 后，从当前稳定分支创建 `codex/*` promotion 分支，提交合并准备变更并创建 PR，按顺序等待 CI/审查后再合并。

Blocked:

- 当前无代码或推送权限阻塞；真实模型、浏览器现场和生产数据库仍属于外部验收环境。

Next action:

- 等待下一项稳定迭代；不主动创建 promotion 分支或 PR。

Validation:

- `mvn -B -DskipTests=false clean test`：155 项测试通过（Core 54、Infrastructure 82、App 19）。
- `D:\Application\miniconda3\python.exe -m scripts.convention_check`：通过。
- `D:\Application\miniconda3\python.exe -m unittest discover -s scripts/tests -p "test_*.py"`：19 项通过。
- `D:\Application\miniconda3\python.exe -m scripts.runtime_eval`：安全边界 36/36、路由与终止 36/36。
- `npm --prefix agent-fronted run typecheck`、`npm --prefix agent-fronted test -- --run`（49/49）和 `npm --prefix agent-fronted run build`：通过。

Preserve:

- 不暂存或提交原工作区 `.idea`、deployment、Docker、Hook 及其他未授权 dirty 资产；恢复它们时保持原删除/修改状态。
- `a086787` stash 和临时 WIP 备份保留，直到用户确认不再需要旧工作区改动。
- 不保存 Prompt、Thinking、API key、完整敏感响应或真实模型原文；`output/runtime_eval` 等报告目录保持忽略。
- `AGENTS.md` 是完整长期规范；本 handoff 只保留当前恢复所需事实。
