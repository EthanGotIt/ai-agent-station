status: completed
updated: 2026-08-24

# Commerce Guardian Agent 交接

## 当前执行阶段

- 当前目标：执行已确认的“继续打磨项目”七阶段流程；本轮不 push。工作树中既有 `.idea`、deployment、Docker、Hook、scripts、AGENTS 和 README 改动均未纳入本轮提交。
- 第一阶段已完成：纯 Item/Turn 投影移至 `agent-console/src/threadProjection.ts`，HTTP 协议边界集中至 `agent-console/src/threadWorkspaceApi.ts`；`useThreadWorkspace` 保留生命周期、SSE 游标、队列动作和恢复缓存，行为不变。
- 第二阶段已完成：订单动作状态投影移至 `agent-console/src/orderActionProjection.ts`，卡内回执由 `agent-console/src/OrderActionStatus.tsx` 渲染；删除独立执行弹窗、右对齐执行气泡和重复全局回执，查询/刷新/写操作均在来源订单卡片内显示排队、确认、重试、核验和完成状态。历史缺少 `ORDER_ACTION_REQUEST` 的外部动作仍保留人工重试降级。
- 第二阶段验证：前端 typecheck、Vitest 28 项、生产构建和 Impeccable detector 均通过（detector 返回 `[]`）；服务保持关闭，MySQL 继续运行。
- 第三阶段已完成：Runtime 的输入清洗与 Item payload 构造分别移至 `AgentTurnInputValidator`、`AgentTurnItemPayloads`；Spring AI 订单 Tool 的参数解析、受控 JSON 和输出截断移至 `SpringAiOrderToolSupport`；Workflow QuestionCard 字段/摘要 schema 移至 `AgentWorkflowQuestionSchema`。原有 `AgentTurnExecutionRouter` 继续保持普通消息、Workflow 回答和订单动作的分派边界，未改变 API、事务、同 Thread FIFO、幂等和远程调用边界。
- 第三阶段验证：Core + Infrastructure 编译通过，57 项 Core 测试和 65 项 Infrastructure 测试通过；服务保持关闭，MySQL 继续运行。下一步唯一动作是为独立 HTTP 订单夹具加入可重复的外部动作失败恢复场景。
- 第四阶段已完成：独立 HTTP 订单夹具新增 `ORDER_SERVICE_FIXTURE_EXPEDITE_TRANSIENT_FAILURES` 故障注入、`FIXTURE_FAULT_ATTEMPTS` 持久计数和 `/_fixture/stats.injectedFailures` 统计；注入失败不创建幂等记录、不改变订单，达到配置次数后同一幂等键只产生一次真实催发货变更。README 与夹具测试同步更新。
- 第四阶段验证：订单服务夹具 5 项测试通过；服务保持关闭，MySQL 继续运行。下一步唯一动作是清理已确认零调用的遗留别名和前端派生路径，并保留旧 Workflow 兼容逻辑。
- 第五阶段已完成：对生产代码与前端路径进行调用点复核，确认 `currentRetryCycleAttemptCount()` 为零调用别名并删除；`manualRetry`、ExternalAction Lease/幂等、旧 Workflow Item 回退、执行回放缓存和 SSE 游标恢复均有实际调用或兼容测试，未误删。未发现可安全删除的前端订单动作/QuestionCard 派生路径。
- 第五阶段验证：Core 外部动作模型定向测试通过；旧 Workflow 兼容测试保留，工作树中用户既有脚本/部署/IDE 改动仍未纳入。下一步唯一动作是建立可复现的 review runbook 与安全启动/停止/status 脚本，供最终现场验收使用。
- 第六阶段已完成：新增 `docs/review-runbook.md` 和 `scripts/review/review-services.ps1`。手册固定物流查询、退款取消/恢复/授权、催发货自动重试/人工恢复三条黄金路径；脚本只管理自身记录的前端 5173、Agent 8090、独立订单夹具 18080 进程，状态/日志/SQLite 均位于系统临时目录，不触碰 MySQL 或用户已有服务。
- 第六阶段验证：PowerShell `status` 解析与空状态运行通过；脚本未启动任何服务。下一步唯一动作是启动隔离夹具、后端和前端，执行完整测试矩阵与四种浏览器尺寸验收，结束后关闭所有测试服务并更新最终文档。
- 第七阶段已完成：完整 Python、Maven、npm 矩阵均通过；真实 5173→8090→18080 现场验证了空退款 `CANCEL`、卡片 `QUERY_LOGISTICS`、催发货 3 次自动失败→人工重试→第 4 次成功，Item 检查器显示超过 8 个完整序列。1920×900、1440×900、1024×768、390×844 均复核了居中 QuestionCard、常驻 Composer、抽屉/移动布局、键盘焦点和卡内动作回执；测试服务已关闭，MySQL 3306 保持运行。
- 第七阶段验证：`python -m scripts.convention_check`、Python 9 项单元测试、`scripts.runtime_eval`、`mvn dependency:analyze -DskipTests`、Maven clean 139 项、前端 typecheck/Vitest 28 项/build 均通过；Impeccable detector 本轮前端改造扫描返回 `[]`。最终文档已同步到 `DESIGN.md`、`docs/architecture.md`、`docs/review-runbook.md`。
- 下一步唯一动作：无；本轮七阶段目标完成，后续如继续开发应从新的需求或现场缺陷单独建立 handoff。
- 已完成的主要提交：`a079c06 feat: deliver the item-driven agent workbench`、`ce642f0 refactor: complete the persisted-item runtime contract`、`4059449 fix: allow workflow cancellation at every question`、`b7b4ea3 feat: add deterministic order action turns`、`569c6f9 feat: fold order actions into source cards`、`edec9ae refactor: separate workflow orchestration responsibilities`、`e99f622 feat: refine conversation and question card proportions`、`d539869 fix: harden persisted workflow startup compatibility`、`104158e fix: clear folded workflow questions`。
- 当前事实源：SSE 对外仅发送 `ready`、`heartbeat` 和 `item.*`；`assistant.delta`、`turn.*`、全局八步进度、固定快捷问、全局订单结果区和 Markdown 表格渲染均已删除。模型最终消息、订单动作、Workflow 状态、外部动作回执和错误通过持久化 Item 恢复，前端用纯函数按 Turn 聚合。
- 确定性订单动作已落地：订单卡片调用 `/api/agent/threads/{threadId}/order-actions`，查询/刷新不调用模型，退款/催发货/隐藏/恢复进入现有 Workflow；`INPUT_KIND`、`ORDER_ACTION_JSON`、`ORDER_ACTION_REQUEST` 支持重启恢复和幂等。回答 `action=CANCEL` 跳过必填校验、关闭 Question、拒绝 Workflow，不产生外部副作用；回答子 Turn 会折回来源卡片并清除已结束 QuestionCard。
- 页面实际结果：顶栏、Thread 列、中央业务流和按需 Item 检查器保持固定工作台层级；消息记录独立滚动，Composer 常驻底部。正文 14px、页面/Turn 标题 16px、卡片标题 15px、技术元数据 11–12px；1920×900、1440×900、1024×768、390×844 均已用 Playwright 现场复核，含 1024 抽屉和移动全屏检查器。
- V6 迁移证据：配置库和一次性克隆库均已备份并由应用实际启动到 Flyway 版本 6；克隆库确认 `AGENT_TURN.INPUT_KIND` 非空、`ORDER_ACTION_JSON` 可空，历史字段安全回填。校准库未启动 V6，旧退款 Workflow 18 条（含等待输入、等待外部动作和人工重试）状态未被改写。备份文件为 `C:\Users\23260\AppData\Local\Temp\commerce-guardian-agent-v6-before-20260824.sql` 与 `C:\Users\23260\AppData\Local\Temp\commerce-guardian-agent-calibration-v6-before-20260824.sql`。
- 最终验证已通过：`python -m scripts.convention_check`、Python 单元测试 8 项、`scripts.runtime_eval`、`mvn dependency:analyze -DskipTests`、Maven clean 测试 139 项、前端 typecheck、Vitest 27 项和生产构建均成功；Impeccable detector 对 `agent-console/src` 返回 `[]`。现场验收结束后已关闭 5173 前端、8090 Agent 和 18080 订单夹具，MySQL 3306 保持运行。

- 当前目标：执行“订单售后 Workflow 推进计划”。实现、真实浏览器验收、独立 HTTP 订单服务现场验收和最终完整矩阵均已完成；目标标记为 `completed`。本轮最终功能提交为 `80c5ca9`，现场验证交接为 `5eb9823`，完成状态交接为 `ed455a6`。
- 已修改范围：统一 `ORDER_SERVICE` 的查询、物流、退款、催发货、隐藏/恢复订单历史能力，完成 QuestionCard、业务进度聚合、Thread 回收站/行内重命名、移动端抽屉、V5 增量迁移、显式外部动作确认和受限 Markdown 表格渲染。未触碰工作树中既有的 IDE、部署、Docker、Hook 和脚本改动。
- 数据库证据：`COMMERCE_GUARDIAN_AGENT` 与 `COMMERCE_GUARDIAN_AGENT_CALIBRATION_20260821` 已由应用实际启动到 Flyway 版本 5；两库均保留演示订单/物流事实，`OPEN_QUESTION_ID`、Turn Workflow 字段、ExternalAction 版本/重试字段、结果表和幂等索引均可读。已确认的 V4 前备份仍保留在 `C:\Users\23260\AppData\Local\Temp\commerce-guardian-agent-before-v4-commerce_guardian_agent-20260823.sql` 与 `C:\Users\23260\AppData\Local\Temp\commerce-guardian-agent-before-v4-commerce_guardian_agent_calibration_20260821-20260823.sql`；该备份早于 V5，已导入专用克隆库 `COMMERCE_GUARDIAN_AGENT_V5_MIGRATION_20260823`，应用实际从版本 3 增量执行 V4、V5，保留 9 条订单、6 条物流事件并成功启动到版本 5。没有另外生成 V5 命名的迁移前备份，但已有前置备份覆盖 V5 前状态，克隆迁移和 V5 后恢复快照均已核验；该点作为已接受的 P2 运维记录保留，不表述为不存在的单独 V5 前备份。
- V5 后恢复快照：已为当前配置库和专用校准库分别生成 `C:\Users\23260\AppData\Local\Temp\commerce-guardian-agent-after-v5-commerce_guardian_agent-20260823.sql`（100160 bytes）与 `C:\Users\23260\AppData\Local\Temp\commerce-guardian-agent-after-v5-commerce_guardian_agent_calibration_20260821-20260823.sql`（335421 bytes）；仅写入临时备份文件，未改动数据库。
- 当前服务状态：本轮验收使用的独立订单服务 `127.0.0.1:18080`、Agent `8090`、前端 `5173` 及临时端口均已关闭；MySQL `127.0.0.1:3306` 保留运行，专用克隆库仅用于迁移证据，未删除或覆盖原配置库。Docker CLI 可用但 Docker Desktop Linux 引擎未启动，因此使用同一台电脑上的独立 Python 进程完成现场 HTTP 验收；Dockerfile/README 已提供可复现容器启动方式。
- 本轮提交：`80c5ca9 feat: add isolated order service fixture`、`5eb9823 docs: record live order service validation`、`e99dd12 docs: document order adapter boundary`、`9d433ca docs: classify migration backup residual risk`、`9fc19af test: verify remote action idempotency replay`、`22eb4de fix: reject invalid order action idempotency keys`、`9c0ce82 test: await question card state transition`、`7029122 fix: propagate order action idempotency`、`b1fc6bc fix: harden order action confirmation UI`、`5532463 fix: align legacy workflow schema`、`093076a fix: tolerate optional order tool input`、`4c7fcc8 fix: align optional order tool formatting`。本轮新增夹具 4 项测试，完整 Python 测试 8 项；最终 Python、Maven、npm 矩阵均通过。每个提交只包含本轮明确路径，未带入用户既有改动。
- 真实浏览器证据：在 `5173 → 8090` 的真实 Vite 代理和真实 DeepSeek V4 Pro 下，验证“列出今天最新订单”、订单卡片查物流、退款原因 QuestionCard、刷新后恢复 QuestionCard、最终授权拒绝无副作用、隐藏后默认查询不再显示、恢复后重新显示、催发货最终授权、Thread Enter 重命名、Escape/失焦取消、ACTIVE→ARCHIVED→ACTIVE、移动端抽屉开关。页面只显示聚合业务进度、订单卡片/物流时间线和受限 Markdown 表格，不显示原始 `assistant.delta`、Tool JSON 或 Thinking。
- 真实 Thread 回收站证据：校准库 Thread `1880e1e7-8122-4843-b140-c5bf8ae9341d` 通过真实 API 完成 ACTIVE 列表 → ARCHIVED 列表 → ACTIVE 恢复，归档时列表不再包含、恢复后重新出现；含开放 Question 的 Thread `7ef9ed22-b02f-4b28-bb04-7f186c76698b` 归档返回 409，拒绝 Question 后开放 Question/未完成动作均为 0，随后归档成功。
- 真实订单动作证据：当前库 `EXTERNAL_ACTION_COMMAND`/`EXTERNAL_ACTION_RESULT` 中 `HIDE_ORDER`、`RESTORE_ORDER`、`EXPEDITE` 均为 `SUCCEEDED` 且各自只产生一个幂等回执；隐藏/恢复后的 `HIDDEN_AT` 最终为 NULL。退款浏览器流程选择拒绝，订单仍保持原状态，证明显式授权前和拒绝路径均没有退款副作用。
- 独立 HTTP 订单服务证据：`scripts/acceptance/order_service_fixture` 使用独立 SQLite 数据库和真实 HTTP 线程提供订单搜索、详情、物流、退款、催发货、隐藏/恢复接口；Agent 通过 `http://127.0.0.1:18080` 查询“列出今天最新订单”实际得到 `ORDER-EXT-TODAY-001`，真实 QuestionCard 授权退款将 `ORDER-EXT-STALLED-001` 从 `SHIPPED` 更新为 `REFUNDED`。随后用同一 Workflow 幂等键重放，服务端幂等记录增量为 0、业务变更增量为 0；实际 Java HTTP 适配器对催发货、隐藏、恢复各发送一次并各重复一次，结果为 `EXPEDITED/HIDDEN/RESTORED`，服务端记录与业务变更各仅增加 3 次。该证据是独立本机服务验收，不伪称为第三方生产订单平台验收。
- 真实 Thread/SSE 证据：此前已验证取消、超时、断线按游标恢复和顺序去重；本轮浏览器进一步验证 QuestionCard 刷新、订单动作期间的业务进度和 Thread 切换。SSE 仍保留为流式输出、取消和断线恢复边界，不再作为右侧开发者运行轨迹展示。
- 当前剩余风险：独立本机 HTTP 订单服务的查询、物流、写操作和服务端幂等已现场验收；Docker Desktop 引擎未启动，容器镜像未现场构建，但同一服务已以独立进程运行并通过 Agent/Java HTTP 适配器验证。若部署到第三方生产订单平台，仍需按其实际鉴权契约另行验收；这属于部署环境风险，不阻塞本仓库目标完成。V5 前置备份的历史流程差异已按已有 V4 前备份、专用克隆迁移和 V5 后快照证据接受为 P2 运维记录。

## 已完成

- 已建立 Agent-first 分包守则，完成 Core、Infrastructure 和 App 的能力内聚迁移。
- 已拆分 Thread、Turn、Item、QuestionCard、ContextSnapshot 存储端口，并保持 MyBatis-Plus 适配器可构建。
- 已将 SSE 事件总线移到 App `agent.stream`，Core 仅保留事件发布和订阅端口。
- 已移除无调用的订单分析、商品明细和近期订单分支，以及对应的直接依赖和冗余导入。
- Item 序号从 1 开始，`afterSequence=0` 可完整恢复首条事实；已增加上下文预算、快照和 Thread FIFO 测试。
- 文档、检查器、测试包路径和前端元数据已对齐 Commerce Guardian Agent / `agent-console` 命名。
- Turn 创建和首个 Item 在 MyBatis 适配器内以 Thread 行锁和同一事务写入；重复 `clientRequestId` 在入队锁内再次检查。
- Item 统一为 `schemaVersion=1 + kind + data`，TURN_STATE、Tool 轨迹、Workflow 状态和外部动作状态均可游标恢复；新增 Turn 执行回放接口。
- Workflow 启动/回答在本地事务写入 QuestionCard、开放问题指针、Workflow 状态和 Workflow Item；协调器在模型失败时也会落 Tool Call/Result 轨迹。
- 上下文摘要继续最新快照的 `throughSequence`，当前 Turn 不重复注入，Tool Result 按配置截断并生成预算报告；上下文读取已改为优先取最新窗口，预算计入当前请求，摘要只跨越已终态 Turn。
- 启用 Java `-Xlint:all,-processing` + `-Werror`、TypeScript 未使用/分支完整性检查和低基数 Runtime 指标。
- SSE 心跳已纳入 `ai-agent.runtime.heartbeat-interval` 配置；订单校验在事务外执行，持久化收口使用事务模板。
- Convention Check 已移除重复的 AgentScope 死检查，并继续扫描前端 TS/TSX/CSS 的遗留命名。
- 提交 `c5ca160 fix: close runtime and provider calibration`：切换到 Spring AI DeepSeek starter，固定 `deepseek-chat`、输出预算、单次重试和 HTTP 超时；协调器改用流式 ChatClient，逐段发布受控 SSE delta，并区分模型故障、取消和 Turn 超时。
- 同一提交修复 Spring Boot 4 实际启动缺口：MyBatis `@Repository` 适配器恢复可代理性，生产边界迁移到 Jackson 3，配置属性记录增加构造绑定。
- 提交 `dd7a5c3 fix: repair workflow answer recovery`：失败回答释放 Question 后，在同一事务中投影当前版本的可重试 Question Item；Item ID 改为受数据库长度约束的 UUID，避免真实浏览器回答路径触发 `ITEM_ID` 截断。
- 提交 `821733c fix: handle closed SSE requests`：将 Servlet 异步连接关闭/超时作为 SSE 生命周期结束处理，避免响应已提交后再次进入通用 JSON 异常边界。
- 提交 `9dba42b fix: preserve external action result type`：修复 MyBatis 外部动作结果实体将 `ACTION_TYPE` 映射到 `type` 导致幂等重放读取为空的问题，统一使用 `actionType` 并补回归测试。
- 提交 `91f2afb fix: align console external action states`：前端按结构化外部动作状态恢复 Turn 展示，保留失败 Turn 的事实终态，接入人工重试 API，并移除不符合当前 SSE 契约的旧结构化事件兼容层。
- 提交 `cef1052 fix: reconnect console SSE after network loss`：网络离线时主动取消 SSE reader，恢复在线后按当前 Item 游标重连；增加 reader 无数据超时兜底，并补组件级重连测试。
- 提交 `d6d22ab fix: align agent input identity boundaries`：普通 Turn 消息、Thread 身份、标题和业务上下文在 Core、HTTP DTO、认证 Header 与 SQL 之间统一边界；客户端请求 ID 统一去空格，避免重试时因表现形式不同绕过幂等查询。
- 提交 `871a155 chore: remove misleading frontend e2e alias`：将实际运行 Vitest Mock 组件测试的旧 `test:e2e` 命名改为 `test:component`，避免把组件替身误报为真实浏览器验收。
- 本里程碑已完成真实 Context 长历史探针：在专用库 `COMMERCE_GUARDIAN_AGENT_CALIBRATION_20260821` 创建一个 Thread，连续提交 30 个失败终态 Turn，得到 180 个 Item、连续序列 1–180 和 6 个快照（版本 1–6，覆盖到序列 156）。应用受控停止并重启后，同一 Thread 的追加 Turn 仍收敛为 `FAILED`，Item 恢复返回 186 条且序列有序；再追加 10 轮后得到 246 个 Item、41 个终态 Turn，快照扩展至版本 8、覆盖到序列 210。持久化 `CONTEXT_ASSEMBLED` 事实记录重启后的 `snapshotThroughSequence=156`，并记录后续 `compressed=true`、`degraded=false`，证明快照读取、压缩和恢复链路实际运行。
- 提交 `5c6f1f5 fix: align normalized turn item facts`：修复普通 Turn 已规范化输入但首个 `USER_MESSAGE` Item 仍保存原始空白的问题；数据库唯一键竞态的重复请求恢复查询也统一使用规范化用户和请求 ID。Core Runtime 4 项聚焦测试覆盖 Item 事实一致性和创建竞态恢复。
- 配置/文档校准：修正 `.env.example` 指向不存在的 MySQL SQL 子目录；追踪矩阵不再复制规则检查器禁止的旧供应商 token。`python -m scripts.convention_check` 重新通过，未放宽检查规则。
- 提交 `a0751c8 docs: align frontend validation command`：只修正 README 中已废弃的 `test:e2e` 命令为真实脚本名 `test:component`；README 其余用户已有新增内容保持未暂存。
- 提交 `94a786c fix: normalize invalid pagination requests`：分页偏移超出 Java `int` 范围和 query 参数类型错误现在统一返回 400 `INVALID_REQUEST`，不再由算术异常落成 500；补充 Core/App 回归测试。
- 提交 `7380257 fix: bound persisted execution errors`：执行失败 Item 只持久化受控错误文案，不再把远端 URL、响应片段或异常消息写入 Item；Workflow 失败测试使用敏感标记验证不会泄露。
- 提交 `9215ed8 chore: declare app test dependencies`：为直接使用 Spring MVC 异常类型的 App 测试补充 test-scope `spring-test`；随后由 `87af4e0` 删除未被 App 源码直接使用的 `spring-core` 声明，保留正确的模块传递边界。
- 提交 `87af4e0 fix: keep app test dependency boundary clean`：将 query 类型异常测试改为反射构造，避免测试实现细节把 `spring-core` 扩大为 App 直接依赖；clean Maven 和 dependency analyze 均通过。
- 提交 `9c073ef fix: align local model environment contract`：清理 `.env.example` 中不再读取的旧 Worker 变量，补齐当前 SSE、Worker 运行时变量；与被忽略 `.env` 的 30 个变量完成同步校验。

## 最近验证

- `python -m scripts.convention_check`：通过；`e83b9c9` 校准了当前 DeepSeek 供应商契约和 Spring Boot 4/Jackson 3 直接依赖规则，仍保留旧项目/旧版本标记等禁用文本检查。
- `python -m unittest discover -s scripts/tests -p "test_*.py"`：8 个测试通过，新增独立 HTTP 订单服务的查询、物流、幂等写操作和 chunked JSON 回归覆盖当前供应商和 JSON 依赖边界。
- 历史基线验证：`mvn clean '-DskipTests=false' test` 曾通过 Core 51、Infrastructure 49、App 16，共 116 项测试；后续阶段新增测试，当前最新矩阵以本文件顶部更新记录为准。
- `mvn -pl commerce-guardian-agent-app -am -DskipTests package`：应用可打包；启动探针实际加载 DeepSeek starter、Tomcat、Hikari 和 MyBatis。
- `mvn -pl commerce-guardian-agent-core -Dtest=AgentTurnRuntimeServiceTest test`：4 项 Runtime 边界测试通过，包含规范化 `USER_MESSAGE` Item 和唯一键创建竞态恢复。
- `mvn dependency:analyze -DskipTests`：`87af4e0` 后 BUILD SUCCESS；Core、Infrastructure 和 App 均报告 `No dependency problems found`，App 仅显式声明直接使用的 test-scope `spring-test`。
- 专用 MySQL 实证：已确认 `127.0.0.1:3306`，创建并导入基线到 `COMMERCE_GUARDIAN_AGENT_CALIBRATION_20260821`；原 `COMMERCE_GUARDIAN_AGENT` 未重建。应用启动、Thread 创建、ACTIVE Turn 重启收敛为 `FAILED/RUNTIME_RESTARTED` 并生成 `TURN_STATE` 已验证；两路回答并发请求只有一路 202、另一路 409，数据库只产生一个回答 Turn，QuestionCard 版本单调推进并可失败对账释放。
- 复核被 Git 忽略的 `commerce-guardian-agent-app/.env` 发现其原有 `AI_AGENT_MODEL_*`、Router/ReAct、旧队列和旧 Worker 参数均不属于当前 `application.yml`；已删除这些旧配置，并补齐当前 `DEEPSEEK_*`、Context、SSE、队列和 Worker 变量，使 `.env` 与 `.env.example` 的变量集合和非敏感默认值一致。Spring Boot 不自动读取 `.env`，运行手册已补充显式加载方式；本地 `DEEPSEEK_API_KEY` 已存在但未打印，`.env.example` 保持空 key。
- 真实 DeepSeek Tool Calling 实证：使用 `.env` 当前 key、真实 `api.deepseek.com`、专用 MySQL 和合成订单查询，Thread `18313ac0-5907-40ec-80c8-63b7ab1b2f7b` 的 Turn `c6780114-356b-4314-bf5d-ec0814e42501` 生成 `lookup_order` Tool Call/Result，调用与结果 `invocationId` 匹配，结果长度 26，最终 `COMPLETED`；所有该 Turn Items 均未包含请求用户 ID或 API key。
- 真实 DeepSeek 流式实证：Thread `e7273c5e-afd4-4587-990a-71c62df9c911` 的 Turn `8c023253-44eb-4414-9c19-092488b289f5` 通过真实 SSE 收到 71 个 `assistant.delta`，并同时收到 `tool_call`、`tool_result`、`item.assistant_message`、`turn.queued/active` 和最终终态事件；未打印 delta 内容或原始 Thinking。
- 真实 DeepSeek 取消/超时实证：流式取消在收到第一段 `assistant.delta` 后调用 `/cancel`，响应 200，Turn 收敛为 `CANCELLED/CLIENT_CANCELLED`；使用临时 `AI_AGENT_THREAD_TURN_TIMEOUT=PT0.5S` 和 `AI_AGENT_STREAM_TIMEOUT=PT1S` 的真实端点探针收敛为 `TIMED_OUT/TURN_TIMEOUT`，日志记录模型流式调用超时。临时超时配置仅注入探针进程，未写入本地配置文件。
- `agent-console/npm run typecheck`、`npm test -- --run`（17 项）、`npm run test:component`（7 项）、`npm run build`：通过；新增人工重试按钮/API、外部动作终态映射和 SSE 重连测试。`test:component` 明确使用 Mock，不作为真实浏览器证据。
- `python -m scripts.runtime_eval`：通过 5 项确定性 Runtime 检查。
- 真实 HTTP acceptance：`python -m scripts.acceptance --base-url http://127.0.0.1:8090 --user-id acceptance-calibration-user` 通过 `thread-list`、`thread-create`、`item-recovery`、`turn-accepted`、`turn-idempotency`、`execution-replay` 六项检查；运行使用专用 MySQL 和本机不可达模型端点，未伪称为真实 DeepSeek 证据。
- Workflow 订单/物流校验已移到本地事务外；Question、WorkflowRun、ExternalActionCommand 和 Workflow Item 的写入由事务模板统一收口。
- Playwright 真实浏览器已连接专用校准库：验证 Thread 创建、重命名、切换、历史 Item 恢复、QuestionCard 拒绝、执行时间线和页面重载后的结果恢复；另验证了本机不可达模型端点的可见失败 Turn。浏览器重载/关闭暴露的 SSE 异步连接异常已由 `821733c` 收口；真实 DeepSeek 另以 HTTP/SSE 探针完成验证。
- Playwright 真实浏览器 SSE 断线续传实证：在 `cal-browser-retry-thread-052603808` 已建立 `afterSequence=13` 的连接后切换 offline，随后通过真实 HTTP 提交 Turn `67fa7ae5-6b61-45cd-bc42-2acd1bd7ce58`；专用 MySQL 记录该 Turn `FAILED/AGENT_EXECUTION_FAILED`，新增 Item 14–19。恢复 online 后浏览器网络记录出现两次 `events?afterSequence=13`，页面按序展示 14–19 号 Item 且无重复，浏览器错误级控制台消息为 0。
- Playwright 真实浏览器人工重试实证：在 Thread `cal-browser-retry-thread-052603808` 点击“人工重试”，页面收到真实 `/retry` 响应和 SSE `EXTERNAL_ACTION_STATUS=SUCCEEDED`；失败 Turn 仍显示失败且不再展示人工重试按钮。页面刷新后仍恢复同一失败 Turn 和最终成功 Item；专用 MySQL 确认命令为 `SUCCEEDED(v7, attempt3, cycle1, lease=null)`、WorkflowRun 为 `COMPLETED(v2)`、Turn 为 `FAILED(v2, EXTERNAL_ACTION_FAILED)`，Item 序列为 1/2/3/4/5，结果表 1 行且幂等键 1 行。
- 当前构建 Playwright 真实浏览器实证：在专用库新建 Thread，通过真实 Vite 代理提交一条普通消息，页面展示 `USER_MESSAGE → QUEUED → ACTIVE → CONTEXT_ASSEMBLED → ERROR → FAILED`；重载后仍恢复同一消息、受控执行错误和终态 Item，网络请求包含 202 入队、`items?afterSequence=0` 和 `events?afterSequence=6`，浏览器错误级控制台消息为 0。截图保存在 `output/playwright/current-browser-calibration.png`；模型端点为本机不可达地址，不作为真实供应商证据。
- 专用 MySQL ExternalAction 实证：单 Worker 成功命令完成且结果表幂等键只有 1 行；故意制造 WorkflowRun 冲突终态后本地投影回滚、Lease 到期接管并复用同一结果，干净校准命令最终 `PROCESSING(v1, attempt1) → SUCCEEDED（版本3，attempt2）`，WorkflowRun/Turn 完成且 Item 序列为 1/2；两个同时启动的 Worker 竞争同一 PENDING 命令时只产生一次远程结果和一次执行。
- 专用 MySQL ExternalAction 重试矩阵：仅在校准库建立失败触发器后，Worker 两次执行失败收敛为 `MANUAL_RETRY_REQUIRED(v4, attempt2, cycle2)`，WorkflowRun 为 `MANUAL_RETRY_REQUIRED(v1)`，Turn 为 `FAILED(v2, EXTERNAL_ACTION_FAILED)`，4 条失败轨迹且无结果；移除触发器后真实 `/retry` 返回原 command/idempotencyKey，Worker 收敛为 `SUCCEEDED(v7, attempt3)`，WorkflowRun `COMPLETED(v2)`，失败 Turn 保持不变，结果表/幂等键各 1 行，重复 `/retry` 返回 409。触发器已从专用库移除。
- 专用库遗留校准数据恢复实证：发现手工历史 Thread 有 4 条 Item 但 `NEXT_SEQUENCE=4`，导致 Worker 重放时重复序列键并持续回滚；仅在已确认的 `COMMERCE_GUARDIAN_AGENT_CALIBRATION_20260821` 将该 Thread 计数修正为 `MAX(SEQUENCE_NO)+1=5`，未修改生产代码或原业务库。应用重启后 Worker 复用既有幂等结果，命令为 `SUCCEEDED(v256, attempt252)`、结果表仍 1 行、Item 序列扩展到 5、WorkflowRun 为 `COMPLETED(v2)`，原失败 Turn 保持 `FAILED(v2)`。
- 专用 MySQL Thread→Turn→Item 并发实证：真实 HTTP 同时提交两个 Thread Turn（Thread `247d4c44-94bb-4c14-ae4f-200ff24b2b3c`），两路均返回 202；MyBatis 通过 Thread 行锁分配了两个 Turn 的 1–12 号 Item，`COUNT=12`、Sequence 全部唯一且连续，两个 Turn 均独立收敛为 `FAILED/AGENT_EXECUTION_FAILED`。在另一专用 Thread `47817a55-f3dc-4d38-950c-533fcd2216e0` 对 Item 插入注入一次性 NOT NULL 故障，接口返回 500 后 Thread/Turn/Item 均为 0 行且 `NEXT_SEQUENCE=0`，触发器已移除。
- 专用 MySQL QuestionCard 并发与事务回滚实证：真实 HTTP 同时回答同一 QuestionCard `cal-q-question-055800521`，只有一路 202、另一路 409；最终为 `ANSWERED（版本3）/CONSUMED`，WorkflowRun 为 `REJECTED(v1)`，仅一个回答 Turn，7 个 Item Sequence 连续且无重复。对 QuestionCard `cal-q-rollback-question-055800521` 在回答 Turn 插入处注入专用库故障后接口返回 500，Question 仍为 `OPEN(v0)/AVAILABLE`、`ANSWER_TURN_ID=NULL`，WorkflowRun 保持 `WAITING_USER_INPUT(v0)`，Thread `NEXT_SEQUENCE=0` 且没有新增 Turn/Item；触发器已移除。

## 本轮实现校准

- 远程回放校准：`9fc19af test: verify remote action idempotency replay` 使用进程内 HTTP 协议服务模拟同一 `Idempotency-Key` 的重复请求，注入第一次本地回执提交失败；两次 HTTP 请求只产生一次业务变更，第三次直接复用本地结果。该测试覆盖真实 HTTP 网关和执行器路径，但仍不等同于外部订单服务验收。
- 最新边界校准：`22eb4de fix: reject invalid order action idempotency keys` 让 HTTP 订单网关的退款、催发货和隐藏/恢复在远程调用前拒绝空或非法幂等键，防止绕过去重契约；新增测试确认三类请求均不出网。`9c0ce82 test: await question card state transition` 修复 React 19 受控 QuestionCard 测试对异步状态渲染的同步时序假设；前端 23 项测试重新全部通过。
- 幂等边界校准：提交 `7029122 fix: propagate order action idempotency` 后，订单写操作端口必须接收 ExternalActionCommand 的幂等键；HTTP 订单适配器将其作为 `Idempotency-Key` 请求头传给退款、催发货和隐藏/恢复接口，本地演示适配器继续在同一事务中完成订单事实与本地回执。随后 `80c5ca9` 已用独立 HTTP 订单服务现场验证服务端重复请求只产生一次业务变更；不将该本机夹具表述为第三方生产平台验收。
- 已建立 [implementation-traceability.md](implementation-traceability.md)，把原始计划目标、当前实现结论和验证证据分开记录；旧 handoff 的“已完成”描述不再单独作为证据。
- 提交 `3e5e4a0 fix: make external action projection atomic`：新增 `ExternalActionOutcomeManager`，将命令 CAS、WorkflowRun、Turn 和结构化外部动作/Turn State Item 放入同一事务；事务提交后才发布实时事件。
- Worker 已区分远程执行异常、本地投影事务失败和提交后的事件发布失败。后两者不会重新执行远程动作；Lease 竞争失败不会产生后续投影。
- `ExternalActionWorkerTest` 当前覆盖 Lease CAS 拒绝、成功收敛和本地投影失败回滚，共 3 项通过。该结果仍未替代真实 MySQL 锁、事务和重启验证。
- 提交 `c927ca0 fix: serialize SSE backlog and live events`：新增 `AgentThreadEventStream`，将每条连接的 backlog、回放期间实时缓冲、ready 和 live 事件串行化，并按 `eventId + sequence` 去重；解决订阅晚绑定和 SSE 发送异常清理边界。
- `AgentThreadEventStreamTest` 当前覆盖回放/实时顺序、并发发布、重复事件和晚绑定订阅清理，共 3 项通过；Servlet 端到端和浏览器重连尚未验证。
- 提交 `21b12e5 fix: guard console thread switching`：前端历史请求和 SSE 连接使用 generation/AbortController；旧 Thread 事件、迟到历史和旧 Turn/Answer 请求不会覆盖当前工作区；QuestionCard 提交值对齐 `APPROVE/REJECT`。
- 前端 typecheck、Vitest 14 项和生产 build 均通过；仍未替代真实浏览器重连和服务端端到端证据。实现过程中按 `vercel-react-best-practices` 检查了异步取消、函数式状态更新和稳定事件边界。
- 提交 `0c836c1 fix: enforce turn version CAS`：`AgentTurnModel` 携带单调 `version`，MyBatis `AGENT_TURN.VERSION_NO` 以 expected-version 条件更新；运行时、外部动作投影和回答失败对账在 CAS 失败时停止或回滚后续事实，终态 Turn 不允许重写。
- Turn 里程碑直接证据：Core 19 项测试、Infrastructure 15 项测试通过；覆盖生命周期版本、MyBatis 更新条件/0 行竞争、终态保护、回答失败事务回滚、已终态恢复和 Worker 调用路径。真实 MySQL 锁/CAS/事务/重启尚未验证。
- 保留工作树中此前已存在的 `.idea`、部署、Docker、Hook 以及未纳入本提交的其他改动；本轮未 reset、checkout 或覆盖这些文件。
- 提交 `04a4c1c fix: harden workflow state transitions`：WorkflowRun 只允许显式状态转换，`COMPLETED/REJECTED/FAILED` 不可重写，人工重试可从 `MANUAL_RETRY_REQUIRED` 重新进入外部动作；QuestionCard reserve 会锁定并核对 Thread 开放指针，回答 CAS 竞争后使用数据库当前读返回并发重复请求的赢家；SQL 增加同一用户/来源 Turn/Workflow 类型唯一约束。
- 状态机里程碑直接证据：Core `AgentWorkflowRunStateTest` 4 项通过；Infrastructure `MybatisAgentWorkflowQuestionStoreTest` 10 项、`TransactionalAgentWorkflowAnswerAdmissionTest` 6 项、`TransactionalAgentWorkflowEngineAnswerTest` 1 项、`MybatisAgentWorkflowRunStoreVersionTest` 2 项、`ExternalActionWorkerTest` 4 项和回答 Turn 持久化测试 1 项通过；覆盖 reserve/enqueue/close/release、重复回答、事务回滚、Thread 指针、人工重试、终态保护和版本条件。真实 MySQL 锁/CAS/事务/重启尚未验证。
- `c5ca160` 的只读审查发现并修复流式超时分类竞态：`Flux.timeout` 现在进入 `AgentExecutionTimeoutException`，Runtime 以 `TIMED_OUT/TURN_TIMEOUT` 收敛，不再误记为模型失败；对应测试已通过。
- `dd7a5c3` 直接证据：失败回答对账 6 项、Workflow Engine Item ID 边界 1 项、Workflow 回答运行时 16 项测试通过；真实浏览器 QuestionCard 拒绝后数据库状态为 `ANSWERED/CONSUMED/REJECTED`，页面重载后不重复展示旧 QuestionCard。
- `821733c` 直接证据：App 异常边界测试 13 项全量通过，其中包含 `AsyncRequestNotUsableException` 和 `AsyncRequestTimeoutException` 的生命周期处理。
- `9dba42b` 直接证据：外部动作结果 Store 回归测试与 Worker 4 项测试通过；真实 MySQL 重启接管后确认 `ACTION_TYPE=REFUND` 正确反序列化，避免幂等重放被误分类为 Worker 异常。
- ExternalAction Worker 真实校准直接证据：失败重试耗尽、人工重试 API、终态 Turn 不重写、重复重试冲突和结果幂等均已在专用 MySQL 完成；未修改产品代码或 SQL 基线。
- `91f2afb` 直接证据：前端 typecheck、16 项 Vitest 和生产 build 通过；真实浏览器人工重试后刷新仍保持失败 Turn 与最终成功外部动作 Item，证明状态映射没有把后续动作结果倒写成新的 Turn 终态。
- `cef1052` 直接证据：前端 typecheck、17 项 Vitest 和生产 build 通过；真实浏览器 offline/online 后从 `afterSequence=13` 重新建立 SSE，恢复断线期间的 14–19 号 Item 且保持有序去重。
- 本轮数据库一致性校准未修改生产代码；真实 MySQL 证据已覆盖 Thread 行锁下的并发 Turn、Item Sequence 唯一性、本地事务回滚、QuestionCard reserve/enqueue/close 的 CAS 竞争和回答入队事务回滚。故障触发器只存在于 `COMMERCE_GUARDIAN_AGENT_CALIBRATION_20260821`，探针结束后已删除。
- 本轮证据文档提交：`c3f67ce docs: record mysql consistency calibration`。
- `e83b9c9 fix: align convention checks with runtime dependencies`：移除与当前 DeepSeek 产品契约冲突的禁止文本项，纠正 Spring Boot 4 使用的 Jackson 3 直接依赖白名单和旧 Jackson 2 禁止项；新增检查器回归测试，未修改用户已有的 AgentScope 规则删除改动。
- 本轮依赖契约追踪文档提交：`80f0870 docs: record dependency contract calibration`。
- `d09ca23 fix: declare runtime direct dependencies`：按实际源码调用路径补齐 Core/Infrastructure/App 的 JSpecify、Jackson 3 core、Reactor、WebFlux、Reactor Netty 和 Netty transport 直接依赖；Maven dependency analyze warning 已清零，未增加新的模块方向。
- 本轮直接依赖追踪文档提交：`9fa3ac7 docs: record direct dependency calibration`。
- `131924a fix: correlate and validate tool calls`：Tool Call/Result Item 生成每次调用唯一 `invocationId` 并按 ID 记录耗时和失败结果；订单号、退款原因等 Tool 参数在网关/Workflow 之前做非空校验，补充同名调用、异常关联和 Workflow 边界测试。
- 本轮 Tool 边界追踪文档提交：`8a2b1cd docs: record tool boundary calibration`。
- `401e856 fix: enforce context history and budget boundaries`：上下文使用最新 Item 窗口而非正序首页，预算报告计入当前请求，摘要边界基于原始终态 Item，摘要失败继续安全降级；新增严格预算、最新窗口和摘要失败回归测试。
- `0ed8688 fix: project bounded model-safe tool results`：订单 Tool 改为受控业务字段投影，移除内部 `userId` 等所有权字段；物流和订单结果在返回模型前统一截断，避免原始 Record 文本直接进入模型和 Item 轨迹；新增敏感字段隔离测试。
- `d6d22ab fix: align agent input identity boundaries`：将 `AgentThreadModel` 的 Thread/User/标题/上下文边界作为 Core 不变量，HTTP DTO 复用同一常量；普通消息 DTO 与 Runtime 同为 256 字符，用户身份与 SQL `USER_ID VARCHAR(128)` 对齐，Workflow 回答仍使用 10000 字符存储列承载结构化输入；新增边界、规范化和 Header 测试。
- `871a155 chore: remove misleading frontend e2e alias`：删除将 Vitest 组件替身称为 E2E 的脚本命名，运行手册改为 `test:component`。
- `94a786c` 直接证据：Core 分页溢出测试和 App query 类型异常测试通过；非法分页请求不再经过 `ArithmeticException` 进入 500，统一返回 400 `INVALID_REQUEST`。
- `7380257` 直接证据：Workflow 失败测试以 `apiKey=DO_NOT_PERSIST` 作为异常标记，验证该标记不出现在该 Turn 的任何 Item 中；执行失败仍保留受控 ERROR 事实和可恢复终态。
- `9215ed8` 直接证据：App 异常/身份聚焦测试 4 项通过；依赖分析从未声明依赖警告收口为三模块 `No dependency problems found`。
- `87af4e0` 直接证据：App 异常测试 2 项通过；clean Maven 116 项和 dependency analyze 三模块均通过，未把 `spring-core` 测试实现细节扩展为 App 直接依赖。
- 当前本地配置/契约直接证据：`.env` 与 `.env.example` 均为 30 个当前变量，非敏感值一致，旧变量为 0；真实 DeepSeek key 只存在于被忽略的 `.env`，未进入 Git。

## 订单售后 Workflow 推进计划执行记录

- `394b5b2 feat: make question cards drive order conversations`：QuestionCard 字段模型现在显式携带 `allowCustom`，固定选项最多三个；Core、MyBatis JSON 解析和演示 Workflow schema 保持一致。允许自定义值的字段仍受字段名、必填、长度和未知字段校验约束，未授权字段不能绕过选项校验。
- 同一提交：前端 QuestionCard 按后端 schema 动态渲染 `SINGLE_SELECT`/`TEXT` 字段、摘要和“其他”自定义输入；QuestionCard 接管 composer，支持 Enter 提交、Shift+Enter 保留换行、中文 IME composing 期间不误提交、Escape/取消按当前拒绝回答协议结束业务确认，并在提交前执行必填和长度约束。
- 同一提交：删除右侧逐条 Execution Ledger 和可见的 `assistant.delta` 事件轨迹；SSE 只更新当前回复气泡，持久化 Item 被聚合为“已整理请求上下文”“已核对订单与物流事实”“等待你的确认”“业务操作已完成”等有限业务进度。回复和 Question prompt 只渲染受限 Markdown，不执行任意 HTML/Markdown。
- 直接验证：前端 Vitest 21 项通过，覆盖动态 QuestionCard、“其他”输入、三选项上限、摘要、受限 Markdown、Enter/Shift+Enter/IME、Escape、delta 隐藏和业务进度聚合；`npm --prefix agent-console run typecheck` 与 `npm --prefix agent-console run build` 通过。Maven Core 53、Infrastructure 49、App 16 项通过。
- 只读审查：`rg` 未发现前端残留 `Execution Ledger`/`execution-inspector`/`itemTrace` 或用于展示原始 Tool/事件 JSON 的代码；Impeccable detector 对本轮前端变更返回 `[]`。SSE 仍保留，原因是它继续承担流式回复、取消和断线重连，而非作为产品信息架构。
- 本阶段未修改 `.env`、`.env.example` 或用户既有 IDE、部署、Docker、Hook、脚本改动；未进行真实浏览器操作，因为本阶段先完成可重复的交互契约测试，真实浏览器将在订单 Workflow 阶段统一验证。
- `13500ba feat: add structured order discovery`：`OrderSearchCriteria` 将时间、金额、状态、关键词、物流停滞、隐藏状态和结果上限收敛为 Core 值；本地 MyBatis 与 HTTP `/orders/search` 均执行用户归属过滤、有限结果和临时失败降级。`OrderSnapshotModel` 增加商品摘要与隐藏时间，新增 `ORDER_LIST`、`ORDER_DETAIL`、`LOGISTICS_TIMELINE` 受控 Item，前端按 Item 聚合为订单卡片与物流时间线。
- 同一提交：V2 Flyway migration 只新增 `ITEM_SUMMARY`、`HIDDEN_AT`、可见性索引和不存在的演示订单/物流事件，使用 `INSERT IGNORE` 不覆盖既有交易事实；基线 SQL 与 migration 字段保持一致。专用校准库先验证迁移，再对当前配置库备份并执行同一增量迁移。
- 同一提交：模型配置与被忽略 `.env`、`.env.example` 同步为 `deepseek-v4-pro`，`application.yml` 开启 thinking 并设置 `reasoning-effort=max`；本阶段没有把 thinking 写入 Item、SSE、日志或摘要。真实 V4 Pro 调用仍未在本阶段重复验证，不能用历史旧模型证据替代。
- 本阶段只读审查发现并修复两个真实问题：`renderOrderSnapshot`/物流事件 JSON 的前导逗号会生成无效结构，已改为确定性对象构造；`LocalOrderGateway` 的测试兼容双构造器缺少 Spring 注入选择，已用明确的两参数构造器注解修复并通过真实应用启动探针。
- `49311ca feat: unify order service workflow`：统一 `start_order_service_workflow`，确定性执行候选筛选、订单/物流核验、意图/订单/原因/最终授权 Question，并将外部动作始终绑定到发起 Run 的 owner Turn；回答 Turn 只完成自身处理，不再持有孤立的等待状态。
- 同一提交：本地退款执行器在一个本地事务中完成订单状态 CAS 更新和幂等结果写入；HTTP 订单适配器把远程退款调用放在本地事务外，再以幂等回执收口。V3 migration 增加 `STEPS_JSON`、`STATE_JSON`、`STEP_NO` 和 `(RUN_ID, STEP_NO)` 唯一约束，开放 Question 快照无数据时返回 204。
- 直接验证：统一 Workflow 测试覆盖候选筛选、结构化答案校验、瞬时答案篡改隔离、三张 Question 顺序、owner Turn、命令幂等键和过期回答拒绝；Maven Infrastructure 53 项、App Question 快照聚焦测试 1 项通过。
- 真实校准库证据：模糊退款在 Thread `c626d931-4be7-47da-8dad-5ba708108301` 完成三步 Question 后，唯一退款命令成功，`ORDER-TODAY-PAID-001` 更新为 `REFUNDED`；命令和结果各 1 行、attempt=1；重复回答 409；停止再启动后 Run 完成且没有开放 Question 或重复回执。
- 历史记录：当时后端与前端实现已闭合但真实浏览器和最终矩阵尚待执行；后续 `fcec19d`、`b1fc6bc` 及 `66fc94a` 已补齐并取得顶部所列浏览器和矩阵证据。SSE 保留为回复流式输出、取消和断线恢复通道，不作为业务进度主界面。

## 下一步唯一动作

无。本目标已完成并暂停；若后续接入第三方生产订单平台，再按其实际鉴权、数据权限和幂等契约执行部署验收。V5 前置备份差异已作为有证据支撑的 P2 运维记录保留。

用户已有的前端目录/SQL 基线重命名、Hook、部署和 IDE 改动保持在工作区，未混入本次阶段提交。
