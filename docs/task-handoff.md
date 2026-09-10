status: completed
updated: 2026-09-10

# Task Handoff

Goal:

- 完成 Commerce Guardian Agent 第一阶段“运行闭环加固”，让模型、工具、持久化事实和 SSE 在同一 Turn 内遵守明确的终止、预算和故障边界。
- 第一阶段及 2A、2B-1、事项级恢复已完成代码与本地/集成门禁；2B-2 生产快照现场故障注入、完整真实模型黄金路径和本轮浏览器 SSE/错误焦点专项已完成。本 handoff 只保留部署环境仍需单独验收的限制。

Completed:

- Core 新增稳定停止原因、输出额度预留/结算、上下文预算检查和相同工具失败熔断；默认累计输出额度为 8,192 token，重复失败阈值为 3。
- Infrastructure 显式装配唯一 `ControlledToolCallingAdvisor` 与顺序执行的 `ControlledToolCallingManager`。FINISH、QuestionCard 或 Workflow 事实成功落库后截断同批剩余工具和额外模型请求；资源停止使用 `STOP_LIMIT`，重复失败使用 `FALLBACK` 并失败收口。
- 每次真实模型请求前按完整 Prompt 估算上下文并预留输出，响应后只结算一次；缺失/零 usage 和断流保守保留预留。正常与错误工具结果统一限制为有效 JSON，并保留标识和截断说明。
- 持久化 Item 成功后，SSE 事件发布失败只记录观测并依赖游标回放，不改写已经提交的 Turn 或业务事实。
- 生产 ContextAssembler 已切换到最新 300 条原始 Item；旧快照继续保留在库中，但第一阶段不再用快照跳过原始历史或提前触发摘要，并记录被裁剪 Item 数量。
- 运行参数、前端停止原因投影、架构文档和运行手册已同步；HTTP 请求格式与既有 Workflow、问答、审批协议保持兼容。
- 新增/调整 Core、Infrastructure、App 与前端测试覆盖同批截断、预算预留和幂等结算、缺失 usage、结果截断、连续失败重置、原始历史读取和前端具体停止原因。
- 2026-09-10 恢复 `D:\Environment\MySQL\my.ini`，保留原 `Data` 目录；在同一 MySQL 8.4 安装下建立隔离 `AcceptanceData` 并导入 SQL 基线，Agent 已成功连接并通过健康检查。
- 2026-09-10 真实 HTTP acceptance runner 通过：Thread/Turn/Item 恢复、开放交互唯一性、刷新恢复、Turn 入队与幂等、执行轨迹回放，以及物流、退款幂等、催发货三次失败后人工恢复；删除场景保持 gated。
- 2026-09-10 带网络权限的真实浏览器验收通过合成订单查询：DeepSeek Tool Calling、`lookup_order`/`search_orders`、`FINISH`、12 个持久化 Item、完成态投影和刷新恢复均通过；1920×900、1440×900、1024×768、390×844 页面可渲染，移动对话抽屉与 Escape 关闭通过，控制台无错误。
- 2A-1/2A-2 已完成固定水位连续读取、Harness 式裁剪/摘要、V2 快照 CAS、溢出恢复和 V9→V10 集成验收；原始 Item 与 SSE 序号不被压缩改写。
- 2B-1 已完成明确订单号催发货图试点的版本路由、订单/资格/确认/命令边界、Worker 结果闭环和 V9→V11 Workflow MySQL 验收；2B-2 技术快照恢复代码已接入，默认开关仍关闭。
- 第三阶段已完成事项级 Continuation owner 锚定、跨问答/审批不重置计数、后台 `RETRY_WAIT` 不唤醒模型、缺失归属安全停止和 `STOP_LIMIT` 幂等，阶段提交为 `86fcbf5`。
- 第四阶段已完成并提交为 `122a659`：确认等待、PENDING/PROCESSING/RETRY_WAIT、成功、成功待核验和人工重试均有明确投影；成功业务结果优先于后续续接失败，前端回归共 66 项。
- 交付文档已同步 2A/2B-1/2B-2 边界、事项级恢复规则、演示步骤、状态语义和现场剩余限制。
- 约定检查器已纳入 `.playwright-cli/` 本地工具产物忽略范围，快照恢复器的可降级异常命名已修正，独立提交为 `8dea5d5`。
- 2026-09-10 追加提交 `003a82f` 完成上下文压缩、Prompt 压力恢复、共享输出额度和受控冲突错误；提交 `9db5d49` 完成 Workflow 编排版本路由、V10/V11 SQL 与持久化边界；`f145a3f` 修复跨 JVM 物流事实指纹不稳定。
- 2026-09-10 2B-2 现场收口：隔离 `AcceptanceData` 开启试点后，删除、损坏 `STATE_JSON`、快照版本失配均在重启后从业务事实重建；未知 Run 版本以 `UNKNOWN_WORKFLOW_ORCHESTRATION_VERSION` 失败且不创建命令。零失败合成夹具完成查询→催发货→批准→Worker→核验→模型总结，命令一次成功、`EXPEDITE_REQUESTED`、幂等记录和业务变更各 1。
- 2026-09-10 浏览器专项在 `1536×730` 通过离线/在线后 `events?afterSequence=18` 重订阅、刷新 Item/SSE 恢复和 QuestionCard 空提交焦点；必填框为 `active + invalid`，出现 `role=alert`，控制台 errors/warnings 为 0。

Decisions:

- 本轮按计划完成 2A-1、2A-2、2B-1、2B-2 现场收口、事项级恢复和结果展示；生产开关、第三方订单平台鉴权和删除动作仍按部署环境独立 gated。
- 保留 Spring AI 2.0.0、同 Thread FIFO、现有恢复路线和 Workflow 事实归属；不增加正常工具调用总次数上限或语义“无进展”判断器。
- 不持久化或展示原始 Thinking；本轮已获得用户授权，提交与推送只包含本阶段明确文件，不纳入 `.impeccable/critique/`。
- 现有 `D:\Environment\MySQL\Data` 不做递归接管或删除；本次验收使用 `D:\Environment\MySQL\AcceptanceData` 隔离实例。基线已包含当前 V9 结构，验收进程关闭 Flyway 自动迁移以避免重复执行 V9，不修改迁移脚本；V10/V11 集成测试使用随机临时库。

TODO:

- 无。后续只需在目标部署环境按运行手册复核生产开关、第三方鉴权、删除动作和其余视口的专项矩阵。

Blocked:

- 当前无代码或测试阻塞。Windows `MySQL84` 服务控制仍返回错误 5，当前验收使用手动启动的隔离 MySQL 进程；如需恢复为 Windows 服务启动，仍需本机服务控制权限。真实内部订单号未发送到外部模型，真实证据以合成订单夹具为限；生产第三方鉴权和删除场景不在本地验收授权内。

Next action:

- 无待恢复动作；若进入部署验收，先核对 `git status --short` 与本 handoff，沿用隔离数据库边界，不接管原 `D:\Environment\MySQL\Data`。

Validation:

- `mvn.cmd -pl commerce-guardian-agent-app -am '-DskipTests=false' test` 本轮完整 reactor 通过（Core 87、Infrastructure 116、App 20，`BUILD SUCCESS`）；此前 `clean` 曾受本地 Agent 占用 jar 影响，因此本轮使用不清理的完整 reactor 门禁。
- `npm --prefix agent-fronted run typecheck`、`npm --prefix agent-fronted test`（66 tests）和 `npm --prefix agent-fronted run build` 通过。
- 第四阶段前端定向验证：`OrderActionStatus.test.tsx` 8 项通过，包含确认等待、PENDING/PROCESSING/RETRY_WAIT、成功待核验刷新、成功回执、人工重试和成功优先级；本轮完整 Maven/Python/前端矩阵已复核通过。
- Python `scripts.convention_check` 和 `unittest discover -s scripts/tests -p "test_*.py"` 通过（19 tests）；`git diff --check` 已通过。
- 2026-09-10 后续复核：隔离 MySQL 3306、订单夹具 18082/18081、Agent 8090/8091、前端 5173 均曾运行；健康检查 `UP`，HTTP acceptance runner、真实 DeepSeek 合成查询与催发货黄金路径、快照故障矩阵、页面刷新恢复和四尺寸布局 smoke 通过。`1536×730` SSE 断线恢复与错误焦点通过；原 `MySQL84` Windows 服务仍因服务控制权限无法直接启动。
- 定向回归：`mvn.cmd -pl commerce-guardian-agent-infrastructure -am '-Dtest=AgentTurnRuntimeServiceTest,MybatisAgentWorkflowRunStoreVersionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过（Core 18、Infrastructure 4）；新增未知编排版本错误码和 Runtime 受控失败断言通过。

Preserve:

- 保留用户已有的 `.impeccable/critique/` 资产及其他未纳入本阶段的工作区改动，不删除、不暂存、不提交。
- 保留数据库中的原始业务事实与旧快照；不保存 Prompt、Thinking、API key、完整敏感响应或真实模型原文。
- `AGENTS.md` 是完整长期规范；本 handoff 只保留当前恢复所需的最小事实。
