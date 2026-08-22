status: active
updated: 2026-08-22

# Commerce Guardian Agent 交接

## 当前执行阶段

- 当前目标：执行“订单售后 Workflow 推进计划”，本阶段只收口数据库迁移、Workflow owner Turn 启动恢复和历史 Question 状态对账，不扩展前端或订单能力范围。
- 已修改范围：Flyway 增量迁移、Workflow owner 恢复候选查询、旧版本 owner Turn 的回答字段兼容，以及对应 Core/Infrastructure 测试；未触碰工作树中既有的 IDE、部署、Docker、Hook 和脚本改动。
- 真实验证：原配置库 `COMMERCE_GUARDIAN_AGENT` 已先生成临时备份 `C:\Users\23260\AppData\Local\Temp\commerce-guardian-agent-original-20260822-v3.sql`，随后应用自身 Flyway 从 baseline v0 增量升级到 v1 并成功启动；专用校准库 `COMMERCE_GUARDIAN_AGENT_CALIBRATION_20260821` 启动恢复后，2 条“Question 已回答但 owner 仍等待输入”的历史记录收敛为 `COMPLETED/REJECTED`，2 条真实开放问题仍保持 `WAITING_USER_INPUT + OPEN + AVAILABLE`。应用已关闭，8090 无监听。
- 本阶段验证：Core 52 项、Infrastructure 49 项测试通过；应用模块安装成功，专用校准库真实启动与恢复对账成功。提交完成后补记提交号。

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
- `python -m unittest discover -s scripts/tests -p "test_*.py"`：4 个测试通过，新增规则回归测试覆盖当前供应商和 JSON 依赖边界。
- `mvn clean '-DskipTests=false' test`：当前基线 Core 51、Infrastructure 49、App 16，共 116 项测试通过；含上下文最新窗口、严格预算、摘要失败降级、Tool 敏感字段投影、流式 delta、模型调用失败、流式超时/取消、Turn 超时收敛、CAS、Workflow、Worker、输入身份、分页异常和执行错误持久化边界测试。
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

## 下一步唯一动作

完成本阶段提交后，进入 QuestionCard 与实时交互收口：先核对回答事务、动态字段契约和 owner Turn 投影，再实现 QuestionCard 接管输入区、Enter/Shift+Enter/IME 规则以及语义进度聚合；不得重新进行已完成的全量调查。

用户已有的前端目录/SQL 基线重命名、Hook、部署和 IDE 改动保持在工作区，未混入本次阶段提交。
