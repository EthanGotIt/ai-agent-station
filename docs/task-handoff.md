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
- 同一提交修复 Spring Boot 4 实际启动缺口：MyBatis `@Repository` 适配器恢复可代理性，生产边界迁移到 Jackson 3，配置属性记录增加构造绑定；全量 Core/Infrastructure/App 测试共 97 项通过。

## 最近验证

- `python -m scripts.convention_check`：通过。
- `python -m unittest discover -s scripts/tests -p "test_*.py"`：3 个测试通过。
- `mvn clean '-DskipTests=false' test`：Core 42、Infrastructure 43、App 12，共 97 项测试通过；含流式 delta、模型调用失败、流式超时/取消、Turn 超时收敛、CAS、Workflow 和 Worker 测试。
- `mvn -pl commerce-guardian-agent-app -am -DskipTests package`：应用可打包；启动探针实际加载 DeepSeek starter、Tomcat、Hikari 和 MyBatis。
- `mvn dependency:analyze -DskipTests`：待本轮脚本/前端阶段完成后再按完整矩阵复核；当前 Java 编译警告失败门禁已通过。
- 专用 MySQL 实证：已确认 `127.0.0.1:3306`，创建并导入基线到 `COMMERCE_GUARDIAN_AGENT_CALIBRATION_20260821`；原 `COMMERCE_GUARDIAN_AGENT` 未重建。应用启动、Thread 创建、ACTIVE Turn 重启收敛为 `FAILED/RUNTIME_RESTARTED` 并生成 `TURN_STATE` 已验证；两路回答并发请求只有一路 202、另一路 409，数据库只产生一个回答 Turn，QuestionCard 版本单调推进并可失败对账释放。
- 当前未发现可用的 DeepSeek 凭据；探针使用假值且将模型端点指向本机不可达地址，仅验证启动和错误收敛，未发送真实用户数据，也不能替代真实 DeepSeek Tool Calling/流式/取消验证。
- `agent-console/npm run typecheck`、`npm test -- --run`、`npm run test:e2e`、`npm run build`：通过。
- `python -m scripts.runtime_eval`：通过 5 项确定性 Runtime 检查。
- Workflow 订单/物流校验已移到本地事务外；Question、WorkflowRun、ExternalActionCommand 和 Workflow Item 的写入由事务模板统一收口。

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

## 下一步唯一动作

读取并遵守 Playwright 浏览器技能说明，启动指向专用校准库的后端和前端，完成真实浏览器 Thread 创建、切换、Item 恢复、QuestionCard/错误重试和 SSE 断线重连验证；同时保持 DeepSeek 真实凭据缺失为明确未验证边界，不把本地替身结果记作真实模型证据。

用户已有的前端目录/SQL 基线重命名、Hook、部署和 IDE 改动保持在工作区，未混入本次阶段提交。
