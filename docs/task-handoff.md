status: active
updated: 2026-09-13

# Task Handoff

Goal:

- 记录当前工作区已经验证的实现、测试和恢复边界，供下一轮协作接续。
- 第一阶段、2A-1/2A-2、2B-1/2B-2、事项级恢复和结果展示属于之前会话中的不同工作线；本 handoff 不把它们重新解释为一个连续总 plan。

Current status (2026-09-13):

- 当前已验证的 LangGraph 实现范围已完成代码实现和回归：`langgraph4j-core:1.8.20`、七节点固定订单图、`WorkflowRun/QuestionCard/Checkpoint/ExternalActionCommand` 业务边界、MyBatis 技术快照 Saver、V8/V11/V12 迁移与恢复路由均已接入；当前定向回归 27/27 通过（引擎 21、图工厂 4、Saver 2）。这不等同于上一会话完整 plan 已全部恢复或完成。
- 已从 Codex 其他会话恢复原始总 plan：“完整收口、质量复核与 DeepSeek Live 评测”。原始计划的七阶段与当前实现的逐项状态已写入 `docs/implementation-traceability.md`；类型化 Item 的主要生产链路已完成，仍需跨层回放/幂等矩阵；DeepSeek 模型契约仍存在明确差距，不能把当前 LangGraph 试点的完成误报为总 plan 完成。
- 2026-09-13 已完成类型化 Item 收口的本轮生产链路迁移：统一 `JacksonAgentItemPayloadCodec`、Core `AgentItemJournal` 和 Infrastructure `TransactionalAgentItemJournal` 已接入；订单事实、外部动作状态、执行事件、错误、续跑、QuestionCard 回答、Workflow 决策、Spring AI Tool 事实、LangGraph 和 Worker Outcome 的生产 Item 写入均通过结构化 Codec/Journal，数据库仍使用原有 `PAYLOAD_JSON` 列，旧历史 envelope 可读。
- 2026-09-13 类型化值模型继续覆盖订单列表/详情、物流时间线、`EXTERNAL_ACTION_STATUS`、`AGENT_CONTINUATION`、`EXECUTION_EVENT` 和 `ERROR`；Items API 在保留 `payload` 字符串的同时新增结构化 `data` 字段，前端显式处理结构化错误，旧字符串 payload 仍作为历史兼容回退。
- LangGraph 的产品范围是受控的订单 Workflow：只有显式订单号的催发货新 Run 在 `AI_AGENT_EXPEDITE_GRAPH_ENABLED=true` 时使用 `EXPEDITE_GRAPH_V1`；历史 Run、补选订单和其他动作保持 `LEGACY_V1`，默认开关仍为 `false`。因此“实现完成”不等于“所有动作已切换”或“生产默认启用”。
- 2B-2 已在隔离 `AcceptanceData` 完成缺失/损坏/版本失配快照、跨进程业务事实重建、未知编排版本受控失败和合成订单真实模型黄金路径；生产开关、第三方鉴权、删除动作及目标环境迁移仍是部署门禁。

Plan provenance:

- 原始总 plan 来源：Codex 任务 `01a01f3f-2a0e-7e52-b70e-4137e4ff3496` 的本地 `PLAN.md`（会话标题“规划核心重构方向”），不是 Git 历史。
- 原始总 plan 的阶段 1 已完成；阶段 2（类型化 Item）主要生产链路已完成，跨层回放/幂等矩阵仍未收口；阶段 4（DeepSeek V4 Flash、关闭 Thinking）仍未对齐；阶段 5 为催发货试点完成、其他 Workflow 保留旧路径；阶段 6/7 还留有发布矩阵和兼容清理事项。逐项证据见 `docs/implementation-traceability.md` 的“已恢复的原始升级计划”。
- `2A-1/2A-2`：上下文历史、压缩、快照 CAS 与溢出恢复的实现事实已验证。
- `2B-1/2B-2`：LangGraph 催发货试点、技术快照恢复和隔离现场事实已验证；`2B` 不是“第三阶段”的别名。
- “第三阶段事项级恢复”和“第四阶段结果展示”是另一组实现追踪项，不据此推断上一会话 plan 的 `3A`。
- 后续 Codex 会话已恢复 `3A` 的独立条目：事项级续跑预算与持久化去重；当前代码已有 `rootTurnId/parentTurnId/cycleNo`、跨问答/审批计数、`STOP_LIMIT` 幂等和缺失归属安全停止证据。该条目与 LangGraph 图的七节点编号保持区分。

Current implementation tracks (not one plan):

- 第一阶段：运行闭环加固——已完成，覆盖终止、同批 Tool 截断、输出/上下文预算和重复工具失败熔断。
- 第二阶段：2A-1/2A-2 上下文与恢复——已完成，覆盖固定水位历史、上下文裁剪/摘要、V2 快照 CAS 和溢出恢复。
- 第三阶段：事项级恢复——已完成，覆盖 Continuation owner、`rootTurnId/parentTurnId/cycleNo`、跨问答/审批计数、后台 `RETRY_WAIT`、缺失归属安全停止和 `STOP_LIMIT` 幂等。
- 第四阶段：结果展示与交付——已完成，覆盖确认等待、外部动作状态、成功结果优先级、成功待核验和人工重试投影。

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
- 2B-1 已完成明确订单号催发货图试点的版本路由、订单/资格/确认/命令边界、Worker 结果闭环和 V9→V12 Workflow MySQL 验收；此前 V9→V11 结果仅保留为历史证据；2B-2 技术快照恢复代码已接入，默认开关仍关闭。
- 第三阶段已完成事项级 Continuation owner 锚定、跨问答/审批不重置计数、后台 `RETRY_WAIT` 不唤醒模型、缺失归属安全停止和 `STOP_LIMIT` 幂等，阶段提交为 `86fcbf5`。
- 第四阶段已完成并提交为 `122a659`：确认等待、PENDING/PROCESSING/RETRY_WAIT、成功、成功待核验和人工重试均有明确投影；成功业务结果优先于后续续接失败，前端回归共 66 项。
- 交付文档已同步 2A/2B-1/2B-2 边界、事项级恢复规则、演示步骤、状态语义和现场剩余限制。
- 约定检查器已纳入 `.playwright-cli/` 本地工具产物忽略范围，快照恢复器的可降级异常命名已修正，独立提交为 `8dea5d5`。
- 本轮聚焦微调已完成：时间线通过 `AgentItemStore.listTurnItems` 按 Turn 目标分页，Runtime 订单事实改为固定水位 300 条流式读取；排队超时 Future 在取消、出队和过期时统一清理，Runtime/SSE 调度器隔离并验证关闭策略。
- 新增/恢复 `AgentItemStoreMySqlIT` 与 `AgentWorkflowMySqlIT`，覆盖 V9→V12 临时库迁移、图快照编排版本、快照 CAS/归属、Workflow 回滚和命令幂等；验收 profile 由约定检查器强制要求匹配的 `@EnabledIfSystemProperty` `*IT.java`。
- 2026-09-10 追加提交 `003a82f` 完成上下文压缩、Prompt 压力恢复、共享输出额度和受控冲突错误；提交 `9db5d49` 完成 Workflow 编排版本路由、V10/V11 SQL 与持久化边界；`f145a3f` 修复跨 JVM 物流事实指纹不稳定。
- 2026-09-10 2B-2 现场收口：隔离 `AcceptanceData` 开启试点后，删除、损坏 `STATE_JSON`、快照版本失配均在重启后从业务事实重建；未知 Run 版本以 `UNKNOWN_WORKFLOW_ORCHESTRATION_VERSION` 失败且不创建命令。零失败合成夹具完成查询→催发货→批准→Worker→核验→模型总结，命令一次成功、`EXPEDITE_REQUESTED`、幂等记录和业务变更各 1。
- 2026-09-10 浏览器专项在 `1536×730` 通过离线/在线后 `events?afterSequence=18` 重订阅、刷新 Item/SSE 恢复和 QuestionCard 空提交焦点；必填框为 `active + invalid`，出现 `role=alert`，控制台 errors/warnings 为 0。
- 2026-09-13 类型化 Item 生产链路迁移已完成：`JacksonAgentItemPayloadCodec`、`TransactionalAgentItemJournal` 覆盖 Runtime、Spring AI Tool 事实、LangGraph、Worker Outcome、Continuation、订单事实、外部动作状态、执行事件和错误；`AgentItemPayloadValue` 已覆盖 18 类公开 payload 并接入相应生产写入；Items API 增加兼容的结构化 `data` 字段，前端显式处理结构化错误，Codec/Journal/Coordinator/LangGraph/Worker/Continuation 定向回归及完整 reactor 均通过，旧数据库列和历史 envelope 保持兼容。

Decisions:

- 本轮已恢复原始总 plan，并确认 2A-1、2A-2、2B-1、2B-2、事项级恢复和结果展示的代码证据；这些条目仍不能覆盖原始计划第 2 阶段的类型化协议和第 4 阶段的模型契约。生产开关、第三方订单平台鉴权和删除动作仍按部署环境独立 gated。
- 保留 Spring AI 2.0.0、同 Thread FIFO、现有恢复路线和 Workflow 事实归属；不增加正常工具调用总次数上限或语义“无进展”判断器。
- 保留旧 `AgentItemStore.listItems` 端口与现有 HTTP/SSE、Thread→Turn→Item 契约；新增 Turn 目标读取为兼容默认方法，MyBatis 使用既有 `(TURN_ID, SEQUENCE_NO)` 索引。
- 不持久化或展示原始 Thinking；本轮已获得用户授权，提交与推送只包含本阶段明确文件，并纳入已确认跟踪范围的 `.impeccable` critique/live 资产。
- 现有 `D:\Environment\MySQL\Data` 不做递归接管或删除；本次验收使用 `D:\Environment\MySQL\AcceptanceData` 隔离实例。基线已包含当前 V9 结构，验收进程关闭 Flyway 自动迁移以避免重复执行 V9，不修改迁移脚本；V10/V11/V12 集成测试使用随机临时库。

TODO:

- 继续完成原始计划第 2 阶段收口：补 Items API 跨页回放、重复/乱序游标安全收口和 typed payload 幂等追加矩阵；保留未知 `kind` 与历史字符串 fallback，保持现有 HTTP、SSE、数据库列和历史 Item 可读。
- 第 2 阶段验收后，单独决定是否将 DeepSeek 契约从当前 `deepseek-v4-pro + thinking` 迁移到原始计划的 `deepseek-v4-flash + thinking disabled`，并补真实 Live 证据。目标部署环境仍需按运行手册复核生产开关、第三方鉴权、删除动作和其余视口矩阵；本轮新增 MySQL IT 需在具备连接变量的环境启用对应 profile。

Blocked:

- 当前无代码或测试阻塞。Windows `MySQL84` 服务控制仍返回错误 5，当前验收使用手动启动的隔离 MySQL 进程；如需恢复为 Windows 服务启动，仍需本机服务控制权限。真实内部订单号未发送到外部模型，真实证据以合成订单夹具为限；生产第三方鉴权和删除场景不在本地验收授权内。

Next action:

- 按 `docs/implementation-traceability.md` 的映射继续第 2 阶段协议验收：补 Items API 跨页回放与重复/乱序游标安全收口测试，再补 typed payload 幂等追加矩阵；未知 `kind` 和历史字符串 fallback 继续保留。保持现有工作区改动、数据库边界和未授权不提交规则不变。

Validation:

- `mvn -pl commerce-guardian-agent-app -am "-DskipTests=false" test` 本轮完整 reactor 通过（243 项，Core、Infrastructure、App 均 `BUILD SUCCESS`，失败和错误均为 0）；此前 `clean` 曾受本地 Agent 占用 jar 影响，因此本轮使用不清理的完整 reactor 门禁。
- 本轮定向回归：`mvn -pl commerce-guardian-agent-core "-Dtest=AgentTurnRuntimeServiceTest,AgentExecutionTimelineServiceTest" test` 通过（Core 21）；`mvn -pl commerce-guardian-agent-app -am "-Dtest=AgentConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过（App 1）。
- `npm --prefix agent-fronted run typecheck`、`npm --prefix agent-fronted test`（75 tests）和 `npm --prefix agent-fronted run build` 通过。
- 第四阶段前端定向验证：`OrderActionStatus.test.tsx` 8 项通过，包含确认等待、PENDING/PROCESSING/RETRY_WAIT、成功待核验刷新、成功回执、人工重试和成功优先级；本轮完整 Maven/Python/前端矩阵已复核通过。
- Python `scripts.convention_check` 和 `unittest discover -s scripts/tests -p "test_*.py"` 通过（19 tests）；`git diff --check` 已通过。
- 2026-09-10 后续复核：隔离 MySQL 3306、订单夹具 18082/18081、Agent 8090/8091、前端 5173 均曾运行；健康检查 `UP`，HTTP acceptance runner、真实 DeepSeek 合成查询与催发货黄金路径、快照故障矩阵、页面刷新恢复和四尺寸布局 smoke 通过。`1536×730` SSE 断线恢复与错误焦点通过；原 `MySQL84` Windows 服务仍因服务控制权限无法直接启动。
- 定向回归：`mvn.cmd -pl commerce-guardian-agent-infrastructure -am '-Dtest=AgentTurnRuntimeServiceTest,MybatisAgentWorkflowRunStoreVersionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过（Core 18、Infrastructure 4）；新增未知编排版本错误码和 Runtime 受控失败断言通过。
- 2026-09-12 当前 LangGraph 定向回归：`mvn -pl commerce-guardian-agent-infrastructure -am "-Dtest=LangGraphWorkflowGraphFactoryTest,LangGraphAgentWorkflowEngineTest,MybatisLangGraphCheckpointSaverTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过（27 项：引擎 21、图工厂 4、Saver 2）。
- 2026-09-13 类型化 Item 定向回归：`mvn -pl commerce-guardian-agent-infrastructure -am "-Dtest=LangGraphAgentWorkflowEngineTest,JacksonAgentItemPayloadCodecTest,TransactionalAgentItemJournalTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过（当前 28 项：引擎 21、Codec 5、Journal 2）。
- 2026-09-13 生产式 Tool 事实定向回归：`mvn -pl commerce-guardian-agent-infrastructure -am "-Dtest=SpringAiAgentToolBoundaryTest,SpringAiAgentTurnCoordinatorTest,LangGraphAgentWorkflowEngineTest,JacksonAgentItemPayloadCodecTest,TransactionalAgentItemJournalTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过（52 项：ToolBoundary 9、TurnCoordinator 15、LangGraph 21、Codec 5、Journal 2）。
- 2026-09-13 订单动作/QuestionCard/Workflow API 定向回归：`mvn -pl commerce-guardian-agent-infrastructure -am "-Dtest=JacksonAgentItemPayloadCodecTest,LangGraphAgentWorkflowEngineTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过（26 项：Codec 5、LangGraph 21）；`mvn -pl commerce-guardian-agent-app -am "-Dtest=AgentThreadControllerTest,AgentConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过（App Controller 3；配置测试未匹配当前测试集）。
- 2026-09-13 Worker/Continuation 回归：`mvn -pl commerce-guardian-agent-infrastructure -am "-Dtest=TransactionalAgentContinuationGatewayTest,ExternalActionWorkerTest,JacksonAgentItemPayloadCodecTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过（20 项：Worker 6、Continuation 9、Codec 5）；`python -m scripts.convention_check` 与 `git diff --check` 通过。
- 2026-09-13 跨层 API/Journal 回归：`mvn -q -pl commerce-guardian-agent-app -am "-Dtest=AgentThreadControllerTest,TransactionalAgentItemJournalTest,JacksonAgentItemPayloadCodecTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过（11 项：Controller 3、Journal 3、Codec 5），覆盖结构化 `data` 恢复和 typed payload 重复追加的序号/事件边界。

Preserve:

- 保留并提交本阶段已确认纳入跟踪范围的 `.impeccable` critique/live 资产；其他未纳入本阶段的工作区改动不删除、不暂存、不提交。
- 保留数据库中的原始业务事实与旧快照；不保存 Prompt、Thinking、API key、完整敏感响应或真实模型原文。
- 2026-09-12 当前工作区存在未提交的前端设计/文案改动；它们不改变本次 LangGraph 结论，恢复时保留并单独验收。
- `AGENTS.md` 是完整长期规范；本 handoff 只保留当前恢复所需的最小事实。
