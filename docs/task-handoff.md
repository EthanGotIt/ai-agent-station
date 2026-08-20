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

## 最近验证

- `python -m scripts.convention_check`：通过。
- `python -m unittest discover -s scripts/tests -p "test_*.py"`：3 个测试通过。
- `mvn -pl commerce-guardian-agent-core -DskipTests=false test`：Core 6 个测试通过。
- `mvn -DskipTests=true compile`：全模块通过，Java 警告失败门禁通过。
- `mvn dependency:analyze -DskipTests`：各模块无依赖问题，白名单仅覆盖框架自动装配和测试引擎静态误报。
- `mvn clean '-DskipTests=false' test`：Core 6 个测试通过；Infrastructure HTTP 测试因当前沙箱禁止 loopback socket 失败，并非业务断言失败。
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
- 保留工作树中此前已存在的 `.idea`、部署、Docker、Hook 以及未纳入本提交的其他改动；本轮未 reset、checkout 或覆盖这些文件。

## 下一步唯一动作

审计并修复 `AgentTurnModel` 到 MyBatis `AGENT_TURN` 的状态更新 CAS：为旧 Worker/恢复流程不能覆盖新状态建立持久版本边界，补充并发更新、终态保护、重启恢复和事务失败测试，并同步 SQL/映射文档；只修改 Turn 持久化边界及直接测试，完成后运行对应 Maven 验证并提交。

用户已有的前端目录/SQL 基线重命名、Hook、部署和 IDE 改动保持在工作区，未混入本次阶段提交。
