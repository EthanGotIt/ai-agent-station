# Commerce Guardian Agent 实现追踪矩阵

> 状态：`active`
> 更新日期：2026-08-23
> 目标来源：任务 `01a01f3f-2a0e-7e52-b70e-4137e4ff3496` 的最新计划、当前工作树、Git 历史、架构文档、SQL、测试和实际运行结果。

本矩阵只把代码、测试和运行结果作为证据。原计划或 `docs/task-handoff.md` 中的“已完成”描述不能单独作为完成证据。

## 订单售后 Workflow 计划追踪

| 计划阶段 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 1. 数据库与状态基线 | 已验证（保留备份流程风险） | `5532463`；当前配置库与专用校准库实际启动到 Flyway 版本 5，`OPEN_QUESTION_ID`、Turn Workflow 字段、ExternalAction 版本/重试字段、结果表和幂等索引均存在。由已确认的 V4 前备份导入专用克隆库 `COMMERCE_GUARDIAN_AGENT_V5_MIGRATION_20260823`，应用实际从版本 3 执行 V4、V5，保留 9 条订单、6 条物流事件并启动到版本 5 | V5 应用前未单独生成 V5 前备份；原库未重建或覆盖，需在后续生产运维窗口补齐备份流程 |
| 2. QuestionCard 与实时交互收口 | 已验证完成 | `b1fc6bc`；动态字段、最多三选项、其他输入、Enter/Shift+Enter/IME、Escape、显式授权空默认值、受限 Markdown 表格、业务进度聚合和 SSE 断线恢复均有测试；真实浏览器刷新后 QuestionCard 恢复，未出现孤立 Waiting 或原始 delta | 无 |
| 3. 订单发现、物流诊断与 V4 Pro 契约 | 已验证完成 | `13500ba`、`56b631e`、`fcec19d`；真实 DeepSeek V4 Pro 查询“列出今天最新订单”和“查物流三天没更新的订单”均完成并产生结构化 `ORDER_LIST`，浏览器展示订单卡片、物流时间线、业务进度和受限 Markdown 表格；最新 jar 的可选物流停滞参数空值/有值 Tool 回归均完成 | 外部 HTTP 订单服务无凭据，真实远程适配器仍仅有契约测试 |
| 4. 统一 `ORDER_SERVICE` Workflow | 已验证（外部远程适配待凭据） | `49311ca`、`6d40351`、`7029122`；真实浏览器完成退款拒绝、隐藏/恢复、催发货的候选核验和最终授权；当前库命令/结果均为单行 `SUCCEEDED`，真实 MySQL 订单隐藏恢复和 DeepSeek Tool Calling 已验证，显式拒绝未产生退款副作用；HTTP 写操作契约测试确认 ExternalAction 幂等键传递到退款、催发货和隐藏/恢复接口 | 外部 HTTP 订单服务无凭据；V5 前备份流程风险已记录 |
| 5. 能力集与产品化收尾 | 已验证完成 | `fcec19d`、`b1fc6bc`；真实浏览器完成 Thread 行内重命名 Enter/Escape、ACTIVE/ARCHIVED 恢复、移动端抽屉、订单卡片上下文动作和聚合进度；typecheck、23 项 Vitest、production build 通过 | 无；外部 HTTP 凭据缺口按阶段 4 记录 |

## 基线结论

- `scripts.convention_check`：通过；首轮失败的原因是用户既有检查器禁止交接/矩阵中的旧备份文件名，修正文档引用后未修改检查规则并重新通过。
- `scripts/tests`：4 个测试通过；规则回归覆盖当前供应商和 JSON 依赖边界，未修改用户既有脚本。
- Maven 阶段 5 后端验证：`mvn clean -DskipTests=false test` 通过 Core 56、Infrastructure 57、App 17；本轮 `7029122` 定向 Infrastructure 幂等键验证 8 项和最新 jar 的两条真实 DeepSeek Tool Calling 查询也通过。
- 前端阶段 5 验证：typecheck、Vitest 23 个测试和 production build 已通过；新增显式授权空默认值与 Markdown 表格渲染测试；组件替身脚本仍明确命名为 `test:component`，未冒充真实浏览器。
- 以上本地测试已补充专用 MySQL 启动、重启恢复、并发回答 HTTP、ExternalAction Worker 状态矩阵和 Context 长历史快照续接实证；真实浏览器已补充 Thread/QuestionCard/重载恢复和 SSE 游标续传证据；真实 DeepSeek 已补充 Tool Calling、流式 delta、流中取消、超时和敏感信息检查。
- 当前工作树包含此前任务产生的功能代码和用户既有的 `.idea`、部署、Docker、Hook 等改动；本轮不得覆盖或混入后者。

## 追踪矩阵

| 目标区域 | 当前结论 | 直接证据 | 优先级 | 下一步 |
| --- | --- | --- | --- | --- |
| 外部动作命令的 Lease、版本、重试与幂等 | 已验证（本地与 HTTP 契约完成，真实远程待凭据） | `ExternalActionCommandModel`、`MybatisExternalActionCommandStore` 已有版本/CAS、Lease、总尝试和重试周期；`ExternalActionOutcomeManager` 在命令 CAS 成功后于同一本地事务投影 WorkflowRun、Turn 和结构化 Item；专用 MySQL 已验证单 Worker 成功、冲突终态投影回滚后 Lease 接管并复用同一结果、双 Worker 竞争只产生一次执行，以及失败触发器下重试耗尽后人工重试复用原命令/幂等键。`7029122` 强制订单写操作端口接收幂等键，HTTP 适配器以 `Idempotency-Key` 请求头发送，执行器四动作传播测试和 HTTP 请求头测试通过。另在专用库发现一条手工历史 Thread 的 `NEXT_SEQUENCE` 小于现有 Item 数量；仅修正校准数据为 `MAX(SEQUENCE_NO)+1` 后重启 Worker，命令成功且结果表仍为单行，未修改生产代码或原业务库 | P0 | 取得外部服务凭据后验证远程去重；保留专用库证据，纳入最终完整矩阵 |
| Thread → Turn → Item 事实一致性与恢复 | 已验证完成 | MyBatis 创建 Turn 与首个 Item 已在 Thread 锁事务内；`AgentTurnModel.version` 与 `AGENT_TURN.VERSION_NO` 形成单调 CAS，运行时在竞争失败时停止后续 Item/SSE，终态不可重写；`dd7a5c3` 将 Workflow Item ID 收敛为 UUID，避免真实数据库 64 字符边界溢出；专用 MySQL 已实证 ACTIVE Turn 重启收敛为 `FAILED/RUNTIME_RESTARTED` 并生成 `TURN_STATE`，两个 HTTP Turn 并发写入时生成 12 个唯一连续 Item Sequence；Item 插入故障返回 500 后 Thread/Turn/Item 全部回滚且 `NEXT_SEQUENCE=0` | P0 | 纳入最终完整矩阵 |
| QuestionCard / Checkpoint / WorkflowRun 状态机 | 已验证完成 | Question admission 已有 `reserve → enqueue → close/release` 的版本 CAS、事务回滚、回答 Turn 幂等和重启对账；`dd7a5c3` 使失败释放与当前版本 Question Item 在同一事务提交，真实浏览器已验证拒绝收敛为 `ANSWERED/CONSUMED/REJECTED` 并在重载后恢复；专用 MySQL 两路 HTTP 并发回答实际得到单个 202/单个 409，最终 Question 为 `ANSWERED（版本3）/CONSUMED`、WorkflowRun 为 `REJECTED(v1)`；回答 Turn 插入故障返回 500 后 Question 保持 `OPEN(v0)/AVAILABLE`、无回答 Turn/Item，Thread 指针和 `NEXT_SEQUENCE=0` 不变 | P0 | 纳入最终完整矩阵 |
| 外部动作成功/失败/人工重试 | 已验证完成 | `ExternalActionOutcomeManager` 统一写入 `EXTERNAL_ACTION_STATUS`、`TURN_STATE`，命令/Workflow/Turn/Item 在本地事务内收敛；专用 MySQL 已验证成功、失败重试耗尽、投影冲突回滚、Lease 接管、结果表单行幂等、双 Worker CAS，以及人工重试不产生第二条结果；校准库遗留序列计数修正后重启恢复为 `SUCCEEDED(v256)`，同一幂等结果仍只有 1 行，原失败 Turn 未被重写 | P0 | 保留专用库证据，纳入最终完整矩阵 |
| 外部动作人工重试状态收口 | 已验证完成 | `04a4c1c` 已验证 `MANUAL_RETRY_REQUIRED → WAITING_EXTERNAL_ACTION/COMPLETED`，真实 API 返回原 command/idempotencyKey；专用 MySQL 已验证耗尽后 API 重试、成功收敛、失败 Turn 不被重写、重复重试返回 409，结果表和幂等键各 1 行；`9dba42b` 修复结果类型映射 | P0 | 保留专用库证据，纳入最终完整矩阵 |
| 类型化 Item 与统一序列日志 | 已验证完成 | Core `AgentItemTypeEnum`、`AgentItemModel` 和 `AgentItemPayloadModel` 强制 `schemaVersion=1 + kind + data` envelope；真实浏览器已展示 `ORDER_LIST`、`ORDER_DETAIL`、`LOGISTICS_TIMELINE` 和受控业务进度，未展示 Tool JSON、事件名或 Thinking | P0 | 无 |
| Context、摘要和敏感信息隔离 | 已验证完成 | `401e856` 让 Context 通过最新窗口查询、当前请求预算和原始终态 Item 识别摘要边界；Core 7 项测试覆盖严格预算、最新窗口、摘要失败降级、快照安全前缀和内部 Item 隔离。`0ed8688` 让订单/物流 Tool 结果只投影模型安全业务字段并在返回前截断，Infrastructure Tool 边界 4 项通过。专用 MySQL 长历史探针实际得到 246 个 Item、8 个快照（版本 1–8，最新覆盖序列 210）；重启后的 `CONTEXT_ASSEMBLED` 事实读取 `snapshotThroughSequence=156`，后续压缩事件为 `compressed=true/degraded=false`。真实 DeepSeek Turn 的所有 Items 未包含请求用户 ID或 API key，Tool Result 长度 26 且 `truncated=false`，完成真实运行时敏感信息检查 | P1 | 纳入最终完整矩阵 |
| Spring AI / DeepSeek 请求契约 | 已验证完成 | `spring-ai-starter-model-deepseek` 保留 `stream().content()`、取消和超时分类；固定 `deepseek-v4-pro`，开启 thinking 与 `reasoning-effort=max`，`.env`/`.env.example` 已同步；真实 V4 Pro Tool Calling、浏览器订单 Workflow、SSE delta、取消、超时和敏感字段均已检查，未将 Thinking 或 delta 写入 Item、日志和前端 | P1 | 外部 HTTP 订单服务凭据缺口不属于 DeepSeek 契约 |
| Tool Calling 与 Workflow 边界 | 已验证完成 | Coordinator 将只读工具与 Workflow 工具分离，写操作进入确定性 Workflow；`131924a` 为每次 Tool Call/Result 写入稳定的 `invocationId`，按调用 ID 记录耗时和失败结果，并在 Tool wrapper 边界拒绝空订单号/退款原因；`0ed8688` 删除订单 Record 的隐式 `toString()` 输出，采用字段白名单和返回前 2000 字符边界；全量 Maven 116 项通过，真实 DeepSeek `lookup_order` Tool Call/Result 的 invocationId 匹配、结果长度 26，真实 SSE/取消/超时也已验证 | P1 | 纳入最终完整矩阵 |
| SSE 断线恢复、去重、有序合并 | 已验证完成 | `AgentThreadEventStream` 已实现单连接 buffer → backlog → ordered flush → live、`eventId + sequence` 去重和晚绑定清理，并有并发单元测试；`cef1052` 让前端在 offline 时取消 reader、online 时从当前游标重连，并以无数据超时兜底；真实浏览器在 `afterSequence=13` 连接上切换 offline/online 后，实际恢复断线期间的 14–19 号 Item，网络记录出现两次 `events?afterSequence=13`，页面无重复且控制台无错误 | P0 | 保留专用 MySQL 与真实浏览器证据，纳入最终完整矩阵 |
| 前端线程切换与 QuestionCard | 已验证完成 | `useThreadWorkspace` 保留 generation、历史 AbortController、旧事件 Thread 过滤和切换期间禁用；QuestionCard 提交 `APPROVE/REJECT`，组件和真实浏览器均覆盖多 Question、刷新恢复、订单动作、归档保护、重命名和移动端抽屉；`91f2afb` 按结构化外部动作状态恢复 Turn 展示并接入人工重试 | P0 | 无 |
| API、SQL、配置、文档一致性 | 已验证（保留两项外部/运维风险） | API/Item envelope/身份边界保持不变；增量 migration 已到 V5，增加 Run 步骤/状态、Question 步骤、外部动作索引和旧库兼容字段；本地退款/催发货/隐藏/恢复与 HTTP `/orders/{id}/refund|expedite|visibility` 契约已同步，`7029122` 明确写操作使用 `Idempotency-Key`；`GET /threads?status=ACTIVE|ARCHIVED`、前端列表、订单卡片、显式确认和 `.env`/`.env.example` 的 `deepseek-v4-pro` 契约已同步 | P0 | 外部 HTTP 订单服务无凭据；V5 前备份流程风险已记录 |
| Runtime eval / acceptance / live eval | 已验证（外部 HTTP 适配器除外） | 当前 runtime eval 是明确标注的确定性本地替身；`871a155` 将前端 Mock 组件脚本改名为 `test:component`；真实 HTTP acceptance 已在专用 MySQL 上通过 Thread 列表、创建、Item 恢复、Turn 入队、幂等和执行轨迹回放六项检查；真实 DeepSeek 和真实浏览器均已取得订单 Workflow、Tool Calling、流式、取消、超时和恢复证据；最终 Python、Maven、npm 矩阵均通过 | P1 | 外部 HTTP 订单服务待专用凭据 |
| 清理旧实现、兼容层和无效测试 | 已验证完成 | `91f2afb` 已删除旧 SSE 结构化事件兼容集合；`rg` 未发现可达的旧供应商配置或实现，规则检查器中的旧 token 仅作为禁用文本回归规则；`871a155` 已清理误导性的 `test:e2e` 命名；被忽略的 `.env` 已删除旧 Router/ReAct、旧队列和旧 Worker 配置，只保留当前变量。runtime eval 的 Fake 类型、前端历史裸 payload fallback 和规则检查器回归文本均有明确边界，不是可证明应删除的生产旧实现；最终矩阵通过 | P2 | 无 |

## 当前里程碑边界

本阶段已闭合 `ORDER_SERVICE` Workflow、真实浏览器产品化、V5 旧库兼容、显式授权、可选 Tool 参数和 HTTP 写操作幂等键边界：`49311ca`、`6d40351`、`fcec19d`、`b1fc6bc`、`5532463`、`093076a`、`4c7fcc8`、`7029122`。真实浏览器、真实 DeepSeek、真实本地 MySQL、迁移专用克隆和本轮完整 Python/Maven/npm 矩阵均已取得直接证据；完整矩阵结果为 Python 规范检查通过、Python 4 项通过、Maven Core 56/Infrastructure 57/App 17、前端 typecheck/Vitest 23 项/build 全部通过。handoff 保持 `active`，原因仍为外部 HTTP 订单服务无凭据和 V5 应用前未单独生成备份两个已记录风险。

## 外部验证边界

本轮追加校准：当前配置库与专用校准库实际启动到 Flyway 版本 5；专用克隆库 `COMMERCE_GUARDIAN_AGENT_V5_MIGRATION_20260823` 从已确认的迁移前备份导入后由版本 3 增量执行 V4、V5，保留 9 条订单、6 条物流事件并成功启动。真实浏览器已完成订单 Workflow、QuestionCard 刷新、Thread 回收站、移动端抽屉和业务进度验收；当前服务已关闭。外部 HTTP 订单服务无凭据，V5 应用前未单独生成 V5 前备份，这两项风险保持未闭合。

本轮追加代码校准：提交 `7029122` 强制所有订单写操作端口接收命令幂等键，HTTP 适配器把该键发送为 `Idempotency-Key` 请求头；四类动作传播测试和 HTTP 请求头契约测试通过。该修复降低远程成功后本地回执提交失败时的重复写入风险，但真实外部订单服务仍需凭据验证其服务端去重实现。

已确认专用校准边界为本机 `127.0.0.1:3306/COMMERCE_GUARDIAN_AGENT_CALIBRATION_20260821`，当前只在该库导入基线；原 `COMMERCE_GUARDIAN_AGENT` 未重建。数据库日志和命令输出均未打印密码；本轮 Thread/QuestionCard 故障触发器只存在于专用库，验证后已移除。Context 长历史探针未删除业务事实，使用独立校准 Thread 并记录 246 个 Item、8 个快照和重启后上下文事件；另对一条手工遗留校准 Thread 的错误 `NEXT_SEQUENCE` 做了仅限该专用库的计数修正，重启后确认 Worker 复用单一幂等结果且未修改生产代码。真实 DeepSeek 已在同一专用库完成 Tool Calling、71 个 SSE delta、流中取消、短时限超时和敏感信息检查；早期“订单售后前端真实浏览器验收尚未开始”属于历史记录，当前浏览器证据见本文顶部及阶段 5 行。复核被 Git 忽略的 App `.env` 后删除了旧 `AI_AGENT_MODEL_*`、Router/ReAct、旧队列和旧 Worker 变量，并使其与 `.env.example` 的变量集合和非敏感默认值一致；Spring Boot 不自动加载该文件，必须显式注入进程，且真实 key 只保留在 `.env`。不能以本地替身替代真实模型证据，也不能把阶段性 P0/P1 运行时证据误报为本计划最终完成。
本阶段新增数据库证据：当前配置库和专用校准库均在确认备份/克隆边界后由 Flyway 从版本 2 增量执行版本 4，`STEPS_JSON`、`STATE_JSON` 为非空，Question 外键恢复，唯一键为 `(RUN_ID, STEP_NO)`，V4 `IDX_EXTERNAL_ACTION_THREAD_STATUS` 已存在；两个库均保留 9 条订单、6 条物流事件，应用实际启动并响应 Thread/Question API 后暂时运行在 8091/8092 供浏览器验收。V3 退款以及 V4 催发货/隐藏/恢复验证均使用专用校准库和真实 DeepSeek Tool Calling，未将密钥或 Thinking 写入数据库；外部 HTTP 订单服务没有可用凭据，因此只保留契约测试和本地真实适配器证据，不伪称外部服务已验收。
