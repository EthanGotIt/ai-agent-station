# Commerce Guardian Agent 实现追踪矩阵

> 状态：`active`
> 更新日期：2026-08-27
> 目标来源：任务 `01a01f3f-2a0e-7e52-b70e-4137e4ff3496` 的最新计划、当前工作树、Git 历史、架构文档、SQL、测试和实际运行结果。

本矩阵只把代码、测试和运行结果作为证据。原计划或 `docs/task-handoff.md` 中的“已完成”描述不能单独作为完成证据。

## V7 整改与 Workflow 框架迁移追踪（进行中）

| 阶段 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 1. LangGraph4j 基础门禁 | 已完成 | `d74bc79`；`langgraph4j-core:1.8.20`、`AGENT_GRAPH_SNAPSHOT` V8、Jackson 3 序列化、MyBatis Saver 和七节点图测试；Core/Infrastructure 编译、LangGraph 定向测试和 `dependency:analyze` 通过 | 生产 Workflow 尚未切换 |
| 2. QuestionCard 与 Workflow Checkpoint 拆分 | 已完成 | `AgentQuestionCardModel/Store`、`AgentWorkflowCheckpointModel/Store`；V9 表和历史迁移；Thread `OPEN_INTERACTION_TYPE/ID`；`AgentQuestionAnswerAdmission`、`AgentWorkflowDecisionAdmission`；新 API/DTO；Core 状态机、MyBatis CAS、Turn 持久化、`request_user_input` 定向测试共 12 项 | 旧模型和兼容 API 在阶段六统一清理 |
| 3. 迁移固定订单 Workflow | 已完成 | `LangGraphAgentWorkflowEngine`、`LangGraphWorkflowGraphFactory.createOrderWorkflow` 和独立 `AgentWorkflowCheckpoint`；七节点拓扑在 `AUTHORIZE` 前中断，QuestionCard/Checkpoint 恢复、事实指纹变化回到 `VERIFY_FACTS`、批准后 ExternalActionCommand 和技术快照重建均有定向测试；本轮补充批准后事实变化时的 `SUPERSEDED → VERIFY_FACTS` 和动作失效安全终态；旧事务引擎已退出生产 Spring 装配 | Worker 完成后的 `VERIFY_OUTCOME/HANDOFF_AGENT` 业务投影和真实外部动作黄金路径纳入阶段七验收 |
| 4. 加固 V7 Continuation | 已完成 | `AgentContinuationGateway`、`TransactionalAgentContinuationGateway`；`AgentContinuationInput.idempotencyKey()` 覆盖根/父 Turn、Run、Command、状态、结果 Sequence 和 cycle；事务内首事实持久化、提交后入队、重复/并发 admission、STOP_LIMIT 和配置边界测试通过；`ExternalActionOutcomeManager` 已移除本地续跑创建并改用统一 Gateway；`e289bcb` 使队列暂满后的续跑重试重新读取持久化 Turn 状态，避免过期快照恢复已取消 Turn | 真实重启恢复、外部动作黄金路径纳入阶段七验收 |
| 5. 前端交互与状态投影 | 已完成 | `agent-fronted` 已统一目录/package；`QUESTION_CARD`、`QUESTION_ANSWER`、`WORKFLOW_CHECKPOINT`、`WORKFLOW_DECISION` 投影与三条新 API；QuestionCard/Checkpoint 独立卡片、历史 `WORKFLOW_QUESTION` 只读展示、七节点 Graph 状态、Continuation 提示、外部成功后的非阻断告警和 Sequence 追加快路径；`e94eb4b` 修正 Agent QuestionCard 合法的 `runId: null`，并覆盖真实 payload；typecheck、Vitest 31 项和 production build 通过 | 真实浏览器四尺寸与黄金路径纳入阶段七 |
| 6. 遗留代码和测试环境清理 | 已完成 | `e7c18c8` 删除旧 Question/Answer 模型、admission、事务 Workflow 引擎、旧 API DTO、Mapper/Store 和旧授权配置；`96b2e27` 删除无生产引用的重复 Workflow Answer 类型，历史 `WORKFLOW_ANSWER` 仍仅按消息标记读取；`01ad541` 修复规范门禁发现的 persistence 包、Clock 注入和测试命名问题。`FakeClientHttpRequestFactoryTest` 覆盖 HTTP 单测，真实 loopback 契约移至 `HttpOrderGatewayIT`/`HttpExternalActionExecutorIT`，Surefire 与 Failsafe 分离；`rg` 未发现旧生产入口或旧前端目录引用；`833765c` 清理依赖分析警告 | 阶段七外部环境和黄金路径验收 |
| 7. 完整验收与交接 | 本地门禁通过，外部环境待复核 | 2026-08-27 本轮 `convention_check`、脚本 9 项、runtime eval 5 项、Maven Core 47/Infrastructure 65/App 17、前端 typecheck/Vitest 31/build 和无警告 `dependency:analyze` 均通过；`bd0fe9a` 收口 Checkpoint 恢复状态与 Spring Bean 装配；`mvn verify` 已进入真实 `*IT`，但 8 项因当前 Windows/JDK 无法建立 loopback selector 报错 | 数据库副本 V7→V8→V9、真实模型/订单夹具/浏览器黄金路径，以及支持网络绑定环境中的 `*IT` 仍需复核 |

阶段二的兼容边界：旧 `AGENT_WORKFLOW_QUESTION`、旧 Turn 列和旧 `WORKFLOW_ANSWER` Item 仅由 V9 迁移脚本或前端历史投影读取；新的生产入口只写独立 QuestionCard/Checkpoint 表和 `QUESTION_ANSWER`/`WORKFLOW_DECISION` Turn。运行时代码不再提供旧 API、旧模型或旧 Workflow admission。详细执行日志不写入 handoff。

## 订单售后 Workflow 计划追踪

| 计划阶段 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 1. 数据库与状态基线 | 已验证（P2 运维差异已接受） | `5532463`；当前配置库与专用校准库实际启动到 Flyway 版本 5，`OPEN_QUESTION_ID`、Turn Workflow 字段、ExternalAction 版本/重试字段、结果表和幂等索引均存在。早于 V5 的已确认 V4 前备份导入专用克隆库 `COMMERCE_GUARDIAN_AGENT_V5_MIGRATION_20260823`，应用实际从版本 3 执行 V4、V5，保留 9 条订单、6 条物流事件并启动到版本 5；当前库和校准库另有 V5 后恢复快照 | 未单独生成 V5 命名的迁移前备份；已有前置备份覆盖 V5 前状态且克隆迁移/恢复快照已验证，作为 P2 运维差异记录，不影响本地数据完整性 |
| 2. QuestionCard 与实时交互收口 | 已验证完成 | `b1fc6bc`；动态字段、最多三选项、其他输入、Enter/Shift+Enter/IME、Escape、显式授权空默认值、受限 Markdown 表格、业务进度聚合和 SSE 断线恢复均有测试；真实浏览器刷新后 QuestionCard 恢复，未出现孤立 Waiting 或原始 delta | 无 |
| 3. 订单发现、物流诊断与 V4 Pro 契约 | 已验证完成 | `13500ba`、`56b631e`、`fcec19d`、`80c5ca9`；真实 DeepSeek V4 Pro 查询“列出今天最新订单”和“查物流三天没更新的订单”均完成并产生结构化 `ORDER_LIST`，浏览器展示订单卡片、物流时间线、业务进度和受限 Markdown 表格；独立 HTTP 订单服务的同类查询和物流时间线也已现场返回；最新 jar 的可选物流停滞参数空值/有值 Tool 回归均完成 | 无；第三方生产订单平台鉴权按部署环境另行验收 |
| 4. 统一 `ORDER_SERVICE` Workflow | 已验证（独立 HTTP 边界完成） | `49311ca`、`6d40351`、`7029122`、`22eb4de`、`80c5ca9`；真实浏览器完成退款拒绝、隐藏/恢复、催发货的候选核验和最终授权；独立 HTTP 订单服务真实响应“今天订单”查询，Agent 真实 QuestionCard 授权退款将远程订单更新为 `REFUNDED`；同一 Workflow 幂等键重放服务端业务变更为 0，Java HTTP 适配器对催发货、隐藏、恢复各重复一次仍只产生一次服务端业务变更；HTTP 写操作契约测试确认幂等键传递，空/非法键在出网前被拒绝 | 无；第三方生产订单平台鉴权和 Docker 容器现场属于部署环境验收；V5 前置备份差异按 P2 接受并记录 |
| 5. 能力集与产品化收尾 | 已验证完成 | `fcec19d`、`b1fc6bc`；真实浏览器完成 Thread 行内重命名 Enter/Escape、ACTIVE/ARCHIVED 恢复、移动端抽屉、订单卡片上下文动作和聚合进度；当前 typecheck、31 项 Vitest、production build 通过 | 无 |

## 本轮阶段七代码评审验收（2026-08-27）

- `C:\Users\23260\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -m scripts.convention_check`：通过。
- `C:\Users\23260\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -m unittest discover -s scripts/tests -p "test_*.py"`：9 项通过；`scripts.runtime_eval` 的 5 个确定性门禁通过。
- `mvn clean '-DskipTests=false' test`：通过，Core 47、Infrastructure 65、App 17。
- `mvn dependency:analyze -DskipTests`：通过，三个 Maven 模块均无依赖问题；删除未使用的 `jspecify` 测试声明并移除 App Controller 冗余 `@Autowired`。
- `npm --prefix agent-fronted run typecheck`、`npm --prefix agent-fronted test -- --run`（31 项）和 `npm --prefix agent-fronted run build`：均通过。
- 代码评审回归：过期 Continuation 队列重试重新读取最新 Turn；批准 Checkpoint 的事实变化会先失效并重核验，动作不再允许时安全失败且不创建命令；Agent QuestionCard 允许 `runId: null`；无引用的旧 Workflow Answer 类型已删除；QuestionCard/Checkpoint Store 可被事务代理，Workflow Decision codec 已注册为 Bean。
- `mvn verify` 已按配置进入真实 `*IT`；`HttpOrderGatewayIT` 与 `HttpExternalActionExecutorIT` 共 8 项因当前 Windows/JDK 环境返回 `Unable to establish loopback connection`，不是 Fake Transport 单测失败。必须在支持网络绑定的环境重跑，不能将该结果记为协议验收通过。
- 按用户要求，本轮未继续启动服务；未重新运行数据库副本迁移、真实模型、订单夹具和浏览器黄金路径。下方历史现场证据继续保留，但不替代本轮重跑。

## 基线结论

- 本地代码门禁已通过：规范检查、脚本 9 项、runtime eval 5 项、Maven Core 47/Infrastructure 65/App 17，以及前端 typecheck、Vitest 31 项和 production build。
- `mvn dependency:analyze -DskipTests` 构建通过且无依赖问题。
- `mvn verify` 的真实 HTTP `*IT` 受到当前环境 loopback selector 限制，8 项错误待网络绑定能力可用时复核；Fake Transport 单测不替代该验收。
- 数据库副本 V7→V8→V9、真实模型/订单夹具/浏览器黄金路径本轮未重跑，历史现场记录需按发布前运行手册再次确认。
- 当前工作树仍包含用户既有的 `.idea`、部署、Docker、Hook、脚本和配置改动；本阶段未覆盖或混入这些改动。

## 追踪矩阵

| 目标区域 | 当前结论 | 直接证据 | 优先级 | 下一步 |
| --- | --- | --- | --- | --- |
| 外部动作命令的 Lease、版本、重试与幂等 | 已验证（本地与独立 HTTP 服务完成） | `ExternalActionCommandModel`、`MybatisExternalActionCommandStore` 已有版本/CAS、Lease、总尝试和重试周期；`ExternalActionOutcomeManager` 在命令 CAS 成功后于同一本地事务投影 WorkflowRun、Turn 和结构化 Item；专用 MySQL 已验证单 Worker 成功、冲突终态投影回滚后 Lease 接管并复用同一结果、双 Worker 竞争只产生一次执行，以及失败触发器下重试耗尽后人工重试复用原命令/幂等键。`7029122` 强制订单写操作端口接收幂等键，HTTP 适配器以 `Idempotency-Key` 请求头发送；`22eb4de` 让空/非法键在出网前失败；`9fc19af` 验证本地回执故障后的协议级重放；`80c5ca9` 的独立 SQLite 订单服务现场验证 Agent 退款和 Java 适配器四类写操作的服务端重复请求只产生一次业务变更。另在专用库发现一条手工历史 Thread 的 `NEXT_SEQUENCE` 小于现有 Item 数量；仅修正校准数据为 `MAX(SEQUENCE_NO)+1` 后重启 Worker，命令成功且结果表仍为单行，未修改生产代码或原业务库 | P0 | 无；第三方生产订单平台鉴权按部署环境另行验收 |
| Thread → Turn → Item 事实一致性与恢复 | 已验证完成 | MyBatis 创建 Turn 与首个 Item 已在 Thread 锁事务内；`AgentTurnModel.version` 与 `AGENT_TURN.VERSION_NO` 形成单调 CAS，运行时在竞争失败时停止后续 Item/SSE，终态不可重写；`dd7a5c3` 将 Workflow Item ID 收敛为 UUID，避免真实数据库 64 字符边界溢出；专用 MySQL 已实证 ACTIVE Turn 重启收敛为 `FAILED/RUNTIME_RESTARTED` 并生成 `TURN_STATE`，两个 HTTP Turn 并发写入时生成 12 个唯一连续 Item Sequence；Item 插入故障返回 500 后 Thread/Turn/Item 全部回滚且 `NEXT_SEQUENCE=0` | P0 | 无；最终矩阵已通过 |
| QuestionCard / Checkpoint / WorkflowRun 状态机 | 已验证完成 | Question admission 已有 `reserve → enqueue → close/release` 的版本 CAS、事务回滚、回答 Turn 幂等和重启对账；`dd7a5c3` 使失败释放与当前版本 Question Item 在同一事务提交，真实浏览器已验证拒绝收敛为 `ANSWERED/CONSUMED/REJECTED` 并在重载后恢复；专用 MySQL 两路 HTTP 并发回答实际得到单个 202/单个 409，最终 Question 为 `ANSWERED（版本3）/CONSUMED`、WorkflowRun 为 `REJECTED(v1)`；回答 Turn 插入故障返回 500 后 Question 保持 `OPEN(v0)/AVAILABLE`、无回答 Turn/Item，Thread 指针和 `NEXT_SEQUENCE=0` 不变 | P0 | 无；最终矩阵已通过 |
| 外部动作成功/失败/人工重试 | 已验证完成 | `ExternalActionOutcomeManager` 统一写入 `EXTERNAL_ACTION_STATUS`、`TURN_STATE`，命令/Workflow/Turn/Item 在本地事务内收敛；专用 MySQL 已验证成功、失败重试耗尽、投影冲突回滚、Lease 接管、结果表单行幂等、双 Worker CAS，以及人工重试不产生第二条结果；校准库遗留序列计数修正后重启恢复为 `SUCCEEDED(v256)`，同一幂等结果仍只有 1 行，原失败 Turn 未被重写 | P0 | 无；最终矩阵已通过 |
| 外部动作人工重试状态收口 | 已验证完成 | `04a4c1c` 已验证 `MANUAL_RETRY_REQUIRED → WAITING_EXTERNAL_ACTION/COMPLETED`，真实 API 返回原 command/idempotencyKey；专用 MySQL 已验证耗尽后 API 重试、成功收敛、失败 Turn 不被重写、重复重试返回 409，结果表和幂等键各 1 行；`9dba42b` 修复结果类型映射 | P0 | 无；最终矩阵已通过 |
| 类型化 Item 与统一序列日志 | 已验证完成 | Core `AgentItemTypeEnum`、`AgentItemModel` 和 `AgentItemPayloadModel` 强制 `schemaVersion=1 + kind + data` envelope；真实浏览器已展示 `ORDER_LIST`、`ORDER_DETAIL`、`LOGISTICS_TIMELINE` 和受控业务进度，未展示 Tool JSON、事件名或 Thinking | P0 | 无 |
| Context、摘要和敏感信息隔离 | 已验证完成 | `401e856` 让 Context 通过最新窗口查询、当前请求预算和原始终态 Item 识别摘要边界；Core 7 项测试覆盖严格预算、最新窗口、摘要失败降级、快照安全前缀和内部 Item 隔离。`0ed8688` 让订单/物流 Tool 结果只投影模型安全业务字段并在返回前截断，Infrastructure Tool 边界 4 项通过。专用 MySQL 长历史探针实际得到 246 个 Item、8 个快照（版本 1–8，最新覆盖序列 210）；重启后的 `CONTEXT_ASSEMBLED` 事实读取 `snapshotThroughSequence=156`，后续压缩事件为 `compressed=true/degraded=false`。真实 DeepSeek Turn 的所有 Items 未包含请求用户 ID或 API key，Tool Result 长度 26 且 `truncated=false`，完成真实运行时敏感信息检查 | P1 | 无；最终矩阵已通过 |
| Spring AI / DeepSeek 请求契约 | 已验证完成 | `spring-ai-starter-model-deepseek` 保留 `stream().content()`、取消和超时分类；固定 `deepseek-v4-pro`，开启 thinking 与 `reasoning-effort=max`，`.env`/`.env.example` 已同步；真实 V4 Pro Tool Calling、浏览器订单 Workflow、SSE delta、取消、超时和敏感字段均已检查，未将 Thinking 或 delta 写入 Item、日志和前端 | P1 | 无 |
| Tool Calling 与 Workflow 边界 | 已验证完成 | Coordinator 将只读工具与 Workflow 工具分离，写操作进入确定性 Workflow；`131924a` 为每次 Tool Call/Result 写入稳定的 `invocationId`，按调用 ID 记录耗时和失败结果，并在 Tool wrapper 边界拒绝空订单号/退款原因；`0ed8688` 删除订单 Record 的隐式 `toString()` 输出，采用字段白名单和返回前 2000 字符边界；最终 Maven 132 项通过，真实 DeepSeek `lookup_order` Tool Call/Result 的 invocationId 匹配、结果长度 26，真实 SSE/取消/超时也已验证 | P1 | 无; 最终矩阵已通过 |
| SSE 断线恢复、去重、有序合并 | 已验证完成 | `AgentThreadEventStream` 已实现单连接 buffer → backlog → ordered flush → live、`eventId + sequence` 去重和晚绑定清理，并有并发单元测试；`cef1052` 让前端在 offline 时取消 reader、online 时从当前游标重连，并以无数据超时兜底；真实浏览器在 `afterSequence=13` 连接上切换 offline/online 后，实际恢复断线期间的 14–19 号 Item，网络记录出现两次 `events?afterSequence=13`，页面无重复且控制台无错误 | P0 | 无；最终矩阵已通过 |
| 前端线程切换与 QuestionCard | 已验证完成 | `useThreadWorkspace` 保留 generation、历史 AbortController、旧事件 Thread 过滤和切换期间禁用；QuestionCard 提交 `APPROVE/REJECT`，组件和真实浏览器均覆盖多 Question、刷新恢复、订单动作、归档保护、重命名和移动端抽屉；`91f2afb` 按结构化外部动作状态恢复 Turn 展示并接入人工重试；`9c0ce82` 修正动态“其他”输入断言的异步状态等待，当前 Vitest 31 项通过 | P0 | 无 |
| API、SQL、配置、文档一致性 | 已验证（独立 HTTP 边界完成） | API/Item envelope/身份边界保持不变；增量 migration 已到 V5，增加 Run 步骤/状态、Question 步骤、外部动作索引和旧库兼容字段；本地与独立 HTTP 订单服务均实现 `/orders/search`、详情、物流和 `/orders/{id}/refund|expedite|visibility` 契约，`7029122` 明确写操作使用 `Idempotency-Key`；新增夹具 README 说明宿主机、Docker 容器和 Compose 网络地址边界；`.env`/`.env.example` 的 `deepseek-v4-pro` 契约仍同步 | P0 | 无；第三方生产鉴权按部署环境另行验收 |
| Runtime eval / acceptance / live eval | 已验证（独立 HTTP 边界完成） | 当前 runtime eval 是明确标注的确定性本地替身；`871a155` 将前端 Mock 组件脚本改名为 `test:component`；真实 HTTP acceptance 已在专用 MySQL 上通过 Thread 列表、创建、Item 恢复、Turn 入队、幂等和执行轨迹回放六项检查；`9fc19af` 增加 HTTP 网关/执行器协议级幂等回放，`80c5ca9` 增加独立 SQLite HTTP 服务的真实 Agent 查询、退款、适配器动作和服务端重放；真实 DeepSeek 和真实浏览器均已取得订单 Workflow、Tool Calling、流式、取消、超时和恢复证据 | P1 | 无；第三方生产鉴权按部署环境另行验收 |
| 清理旧实现、兼容层和无效测试 | 已验证完成 | `91f2afb` 已删除旧 SSE 结构化事件兼容集合；`rg` 未发现可达的旧供应商配置或实现，规则检查器中的旧 token 仅作为禁用文本回归规则；`871a155` 已清理误导性的 `test:e2e` 命名；被忽略的 `.env` 已删除旧 Router/ReAct、旧队列和旧 Worker 配置，只保留当前变量。runtime eval 的 Fake 类型、前端历史裸 payload fallback 和规则检查器回归文本均有明确边界，不是可证明应删除的生产旧实现；最终矩阵通过 | P2 | 无 |

## 当前里程碑边界

阶段一至六已由 `d74bc79`、`fa834b5`、`dbf4aa5`、`ebada3f`、`db73491`、`e7c18c8` 和 `01ad541` 完成并分别可回滚。本轮阶段七已闭合本地代码门禁，但 `mvn verify` 的真实 loopback `*IT`、数据库副本 V7→V8→V9、真实模型/订单夹具/浏览器黄金路径尚未在当前环境重新执行；因此本矩阵和 handoff 保持 `active`，不能写成最终 `completed`。历史现场证据仍保留在下方，但必须与本轮结果区分。

## 历史外部验证边界（不替代本轮阶段七重跑）

本轮追加校准：当前配置库与专用校准库实际启动到 Flyway 版本 5；专用克隆库 `COMMERCE_GUARDIAN_AGENT_V5_MIGRATION_20260823` 从已确认的、早于 V5 的迁移前备份导入后由版本 3 增量执行 V4、V5，保留 9 条订单、6 条物流事件并成功启动；当前库和校准库均有 V5 后恢复快照。真实浏览器已完成订单 Workflow、QuestionCard 刷新、Thread 回收站、移动端抽屉和业务进度验收；本轮独立 HTTP 订单服务也已完成 Agent 查询、退款、适配器动作和服务端幂等重放验收；Docker Linux 引擎未启动，不伪称存在容器现场证据。V5 未单独生成命名备份的差异已按 P2 运维记录接受。

本轮追加代码校准：提交 `7029122` 强制所有订单写操作端口接收命令幂等键，HTTP 适配器把该键发送为 `Idempotency-Key` 请求头；四类动作传播测试和 HTTP 请求头契约测试通过。该修复降低远程成功后本地回执提交失败时的重复写入风险，但真实外部订单服务仍需凭据验证其服务端去重实现。

本轮独立 HTTP 验收：提交 `80c5ca9` 新增不共享 Agent MySQL 的订单服务夹具。真实 Agent 通过 `127.0.0.1:18080` 完成订单搜索和退款 QuestionCard，远程 `ORDER-EXT-STALLED-001` 最终为 `REFUNDED`；同一 Workflow 幂等键重放不新增服务端记录或业务变更。实际 Java `HttpOrderGateway` 对 `EXPEDITE`、`HIDDEN`、`ACTIVE` 各执行一次并重复一次，服务端返回稳定结果且业务变更各仅一次；夹具测试还覆盖 Java 客户端使用的 chunked JSON 请求体。该服务可由独立进程运行，也可按 README 使用 Docker Desktop；当前 Docker Linux 引擎未启动，不将容器未启动伪称为容器证据。

已确认专用校准边界为本机 `127.0.0.1:3306/COMMERCE_GUARDIAN_AGENT_CALIBRATION_20260821`，当前只在该库导入基线；原 `COMMERCE_GUARDIAN_AGENT` 未重建。数据库日志和命令输出均未打印密码；本轮 Thread/QuestionCard 故障触发器只存在于专用库，验证后已移除。Context 长历史探针未删除业务事实，使用独立校准 Thread 并记录 246 个 Item、8 个快照和重启后上下文事件；另对一条手工遗留校准 Thread 的错误 `NEXT_SEQUENCE` 做了仅限该专用库的计数修正，重启后确认 Worker 复用单一幂等结果且未修改生产代码。真实 DeepSeek 已在同一专用库完成 Tool Calling、71 个 SSE delta、流中取消、短时限超时和敏感信息检查；早期“订单售后前端真实浏览器验收尚未开始”属于历史记录，当前浏览器证据见本文顶部及阶段 5 行。复核被 Git 忽略的 App `.env` 后删除了旧 `AI_AGENT_MODEL_*`、Router/ReAct、旧队列和旧 Worker 变量，并使其与 `.env.example` 的变量集合和非敏感默认值一致；Spring Boot 不自动加载该文件，必须显式注入进程，且真实 key 只保留在 `.env`。不能以本地替身替代真实模型证据，也不能把阶段性 P0/P1 运行时证据误报为本计划最终完成。
本阶段新增数据库证据：当前配置库和专用校准库均在确认备份/克隆边界后由 Flyway 从版本 2 增量执行版本 4，`STEPS_JSON`、`STATE_JSON` 为非空，Question 外键恢复，唯一键为 `(RUN_ID, STEP_NO)`，V4 `IDX_EXTERNAL_ACTION_THREAD_STATUS` 已存在；两个库均保留 9 条订单、6 条物流事件，应用实际启动并响应 Thread/Question API 后暂时运行在 8091/8092 供浏览器验收。V3 退款以及 V4 催发货/隐藏/恢复验证均使用专用校准库和真实 DeepSeek Tool Calling，未将密钥或 Thinking 写入数据库；本轮新增的独立 HTTP 订单服务使用独立 SQLite 并完成查询、物流、Agent 退款、Java 适配器四类写操作和服务端幂等验证，不把该本机夹具表述为第三方生产平台。
