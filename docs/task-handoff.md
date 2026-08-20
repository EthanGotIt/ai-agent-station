status: active
updated: 2026-08-21

# Commerce Guardian Agent 交接

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
- 上下文摘要继续最新快照的 `throughSequence`，当前 Turn 不重复注入，Tool Result 按配置截断并生成预算报告；增加确定性 `scripts.runtime_eval` 门禁。
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

## 最近验证

- `python -m scripts.convention_check`：通过；`e83b9c9` 校准了当前 DeepSeek 供应商契约和 Spring Boot 4/Jackson 3 直接依赖规则，仍保留旧项目/旧版本标记等禁用文本检查。
- `python -m unittest discover -s scripts/tests -p "test_*.py"`：4 个测试通过，新增规则回归测试覆盖当前供应商和 JSON 依赖边界。
- `mvn clean '-DskipTests=false' test`：Core 42、Infrastructure 45、App 13，共 100 项测试通过；含流式 delta、模型调用失败、流式超时/取消、Turn 超时收敛、CAS、Workflow 和 Worker 测试。
- `mvn -pl commerce-guardian-agent-app -am -DskipTests package`：应用可打包；启动探针实际加载 DeepSeek starter、Tomcat、Hikari 和 MyBatis。
- `mvn dependency:analyze -DskipTests`：BUILD SUCCESS；Core、Infrastructure 和 App 均报告 `No dependency problems found`。
- 专用 MySQL 实证：已确认 `127.0.0.1:3306`，创建并导入基线到 `COMMERCE_GUARDIAN_AGENT_CALIBRATION_20260821`；原 `COMMERCE_GUARDIAN_AGENT` 未重建。应用启动、Thread 创建、ACTIVE Turn 重启收敛为 `FAILED/RUNTIME_RESTARTED` 并生成 `TURN_STATE` 已验证；两路回答并发请求只有一路 202、另一路 409，数据库只产生一个回答 Turn，QuestionCard 版本单调推进并可失败对账释放。
- 当前未发现可用的 DeepSeek 凭据；探针使用假值且将模型端点指向本机不可达地址，仅验证启动和错误收敛，未发送真实用户数据，也不能替代真实 DeepSeek Tool Calling/流式/取消验证。
- `agent-console/npm run typecheck`、`npm test -- --run`（17 项）、`npm run test:e2e`、`npm run build`：通过；新增人工重试按钮/API、外部动作终态映射和 SSE 重连测试。
- `python -m scripts.runtime_eval`：通过 5 项确定性 Runtime 检查。
- Workflow 订单/物流校验已移到本地事务外；Question、WorkflowRun、ExternalActionCommand 和 Workflow Item 的写入由事务模板统一收口。
- Playwright 真实浏览器已连接专用校准库：验证 Thread 创建、重命名、切换、历史 Item 恢复、QuestionCard 拒绝、执行时间线和页面重载后的结果恢复；另验证了本机不可达模型端点的可见失败 Turn。浏览器重载/关闭暴露的 SSE 异步连接异常已由 `821733c` 收口；真实 DeepSeek 仍未验证。
- Playwright 真实浏览器 SSE 断线续传实证：在 `cal-browser-retry-thread-052603808` 已建立 `afterSequence=13` 的连接后切换 offline，随后通过真实 HTTP 提交 Turn `67fa7ae5-6b61-45cd-bc42-2acd1bd7ce58`；专用 MySQL 记录该 Turn `FAILED/AGENT_EXECUTION_FAILED`，新增 Item 14–19。恢复 online 后浏览器网络记录出现两次 `events?afterSequence=13`，页面按序展示 14–19 号 Item 且无重复，浏览器错误级控制台消息为 0。
- Playwright 真实浏览器人工重试实证：在 Thread `cal-browser-retry-thread-052603808` 点击“人工重试”，页面收到真实 `/retry` 响应和 SSE `EXTERNAL_ACTION_STATUS=SUCCEEDED`；失败 Turn 仍显示失败且不再展示人工重试按钮。页面刷新后仍恢复同一失败 Turn 和最终成功 Item；专用 MySQL 确认命令为 `SUCCEEDED(v7, attempt3, cycle1, lease=null)`、WorkflowRun 为 `COMPLETED(v2)`、Turn 为 `FAILED(v2, EXTERNAL_ACTION_FAILED)`，Item 序列为 1/2/3/4/5，结果表 1 行且幂等键 1 行。
- 专用 MySQL ExternalAction 实证：单 Worker 成功命令完成且结果表幂等键只有 1 行；故意制造 WorkflowRun 冲突终态后本地投影回滚、Lease 到期接管并复用同一结果，干净校准命令最终 `PROCESSING(v1, attempt1) → SUCCEEDED（版本3，attempt2）`，WorkflowRun/Turn 完成且 Item 序列为 1/2；两个同时启动的 Worker 竞争同一 PENDING 命令时只产生一次远程结果和一次执行。
- 专用 MySQL ExternalAction 重试矩阵：仅在校准库建立失败触发器后，Worker 两次执行失败收敛为 `MANUAL_RETRY_REQUIRED(v4, attempt2, cycle2)`，WorkflowRun 为 `MANUAL_RETRY_REQUIRED(v1)`，Turn 为 `FAILED(v2, EXTERNAL_ACTION_FAILED)`，4 条失败轨迹且无结果；移除触发器后真实 `/retry` 返回原 command/idempotencyKey，Worker 收敛为 `SUCCEEDED(v7, attempt3)`，WorkflowRun `COMPLETED(v2)`，失败 Turn 保持不变，结果表/幂等键各 1 行，重复 `/retry` 返回 409。触发器已从专用库移除。
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

## 下一步唯一动作

审计 Context/Tool/DeepSeek 与 API/SQL 契约，逐项证明旧实现和兼容层是否可删除，并运行完整检查矩阵；DeepSeek 凭据仍缺失时，继续明确记录真实 Tool Calling、流式、取消和超时为未验证，不用本地替身冒充证据。

用户已有的前端目录/SQL 基线重命名、Hook、部署和 IDE 改动保持在工作区，未混入本次阶段提交。
