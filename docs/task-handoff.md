status: active
updated: 2026-09-10

# Task Handoff

Goal:

- 完成 Commerce Guardian Agent 第一阶段“运行闭环加固”，让模型、工具、持久化事实和 SSE 在同一 Turn 内遵守明确的终止、预算和故障边界。
- 第一阶段及 2A、2B-1、事项级恢复已完成代码与本地/集成门禁；当前交付边界保留 2B-2 生产快照现场故障注入、完整真实模型黄金路径和浏览器剩余矩阵。

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

Decisions:

- 本轮按计划完成 2A-1、2A-2、2B-1、事项级恢复和结果展示；2B-2 已有实现与定向测试，但生产副本故障注入、跨进程现场证据和完整启用验收仍单独保留。
- 保留 Spring AI 2.0.0、同 Thread FIFO、现有恢复路线和 Workflow 事实归属；不增加正常工具调用总次数上限或语义“无进展”判断器。
- 不持久化或展示原始 Thinking；本轮已获得用户授权，提交与推送只包含本阶段明确文件，不纳入 `.impeccable/critique/`。
- 现有 `D:\Environment\MySQL\Data` 不做递归接管或删除；本次验收使用 `D:\Environment\MySQL\AcceptanceData` 隔离实例。基线已包含当前 V9 结构，验收进程关闭 Flyway 自动迁移以避免重复执行 V9，不修改迁移脚本；V10/V11 集成测试使用随机临时库。

TODO:

- 完成 2B-2 现场收口：在一次性副本开启 `AI_AGENT_EXPEDITE_GRAPH_ENABLED=true`，注入删除/损坏/版本失配的 `AGENT_GRAPH_SNAPSHOT` 并验证业务事实重建、跨进程恢复和未知版本拒绝；再补全“查询 → 催发货 → 批准 → Worker → 结果 → 模型总结”真实路径及浏览器 SSE/错误焦点矩阵。

Blocked:

- 当前无代码或测试阻塞。Windows `MySQL84` 服务控制仍返回错误 5，当前验收因此使用手动启动的隔离 MySQL 进程；如需恢复为 Windows 服务启动，仍需具备本机服务控制权限。真实内部订单号未发送到外部模型，真实模型证据以合成订单查询为限；完整催发货模型路径、2B-2 故障注入和删除场景仍按运行手册 gated。

Next action:

- 恢复任务时先核对 `git status --short` 与本 handoff；从 2B-2 一次性副本故障注入开始，不回退已提交的阶段改动。若需要持久化 MySQL84 服务启动，再单独申请本机服务控制权限并复核原 `Data` 目录。

Validation:

- `mvn clean '-DskipTests=false' test` 在 App clean 阶段被仍运行的本地 Agent 占用 jar 阻断；随后 `mvn -pl commerce-guardian-agent-app -am '-DskipTests=false' test` 完整 reactor 通过（Core 86、Infrastructure 115、App 20，`BUILD SUCCESS`）。
- `npm --prefix agent-fronted run typecheck`、`npm --prefix agent-fronted test`（66 tests）和 `npm --prefix agent-fronted run build` 通过。
- 第四阶段前端定向验证：`OrderActionStatus.test.tsx` 8 项通过，包含确认等待、PENDING/PROCESSING/RETRY_WAIT、成功待核验刷新、成功回执、人工重试和成功优先级；合并前需再运行完整 Maven/Python/前端矩阵。
- Python `scripts.convention_check` 和 `unittest discover -s scripts/tests -p "test_*.py"` 通过（19 tests）；`git diff --check` 已通过。
- 2026-09-10 后续复核已替换上述阻塞结论：隔离 MySQL 3306、订单夹具 18080、Agent 8090、前端 5173 均运行；健康检查 `UP`，HTTP acceptance runner 通过，真实 DeepSeek 合成查询完成并产生 12 个 Item，页面刷新恢复和四尺寸浏览器截图通过。原 `MySQL84` Windows 服务仍因服务控制权限无法直接启动。

Preserve:

- 保留用户已有的 `.impeccable/critique/` 资产及其他未纳入本阶段的工作区改动，不删除、不暂存、不提交。
- 保留数据库中的原始业务事实与旧快照；不保存 Prompt、Thinking、API key、完整敏感响应或真实模型原文。
- `AGENTS.md` 是完整长期规范；本 handoff 只保留当前恢复所需的最小事实。
