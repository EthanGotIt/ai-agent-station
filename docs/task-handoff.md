status: active
updated: 2026-08-28

# Task Handoff

Goal:

- 完成 V7 整改与 Workflow 框架迁移：Spring AI 负责模型与 Tool Calling，LangGraph4j 负责确定性订单 Workflow；QuestionCard 只提问，Workflow Checkpoint 独立承载执行确认，并清理旧运行时路径。

Completed:

- 已完成 LangGraph4j 基础门禁、QuestionCard/Workflow Checkpoint 拆分、固定七节点 Workflow、Continuation 生命周期加固和前端双卡片交互。
- 已删除旧 Workflow Question/Answer Core 模型、Store、admission、failure reconciler、事务推进引擎、旧 HTTP DTO、旧控制器入口和旧授权兼容构造器。
- 运行时只读取新的 QuestionCard、Workflow Checkpoint、Question Answer 和 Workflow Decision；历史 `WORKFLOW_QUESTION`/`WORKFLOW_ANSWER` 仅保留迁移与只读投影边界。
- 已将 HTTP 协议单测改为 Fake Transport，并把真实 loopback 测试重命名为 `*IT`；Maven Surefire 只运行 `*Test`，Failsafe 负责 `*IT`。
- 已清理生产代码、迁移说明、架构/运行手册和脚本中的旧前端与旧授权运行时引用。
- 已修复规范门禁发现的 persistence 包、Clock 注入和测试命名问题；阶段六整改与该独立修复均已提交。
- 本轮代码评审已收口续跑重试的过期 Turn 快照、提交后入队取消竞态、批准后事实变化的 Checkpoint 失效/重核验、事实变化时拒绝决策的终态收口、Agent QuestionCard 的空 `runId` 前端解析、重复 Workflow Answer 类型，以及事务代理和 Jackson 决策编解码器的启动装配问题；对应修复已按独立 `fix:`/`refactor:` 提交。
- 本轮本地验收已通过 convention、脚本 9 项、runtime eval 5 项、`mvn clean '-DskipTests=false' test`（core 48、infrastructure 67、app 17）、前端 typecheck/Vitest 31 项、production build 和无警告的 `mvn dependency:analyze -DskipTests`。
- 2026-08-28 已确认 Windows/JDK loopback pipe 失败来自宿主机 Java 临时目录继承，而非项目 HTTP 实现；项目兼容代码已完全撤销。用户级 `TEMP/TMP` 恢复为同一用户临时目录，Java 通过宿主机 `jdk.net.unixdomain.tmpdir` 使用短系统临时目录；裸 `Selector.open()` 通过，从干净编译产物启动原始应用后 `8090` 健康状态为 `UP`。
- 2026-08-28 已修复退款/催发货失败：先创建 WorkflowRun 再写 `AGENT_GRAPH_SNAPSHOT`，消除外键顺序错误；MyBatis Saver 改为不受 Spring 持久化代理的组件，由 Workflow Engine 外层事务统一管理，避免 LangGraph 内部锁被 CGLIB 代理破坏。真实退款与催发货均完成并核验，订单状态分别为 `REFUNDED` 与 `EXPEDITE_REQUESTED`；前端同时兼容 Workflow 回执的 `status` 和金额字符串，并让空 Thread 内容轨道与 Composer 对齐。

Decisions:

- 本文件是“最新状态快照 + 唯一下一步指针”，不累计提交列表或过程日志；历史提交以 Git 历史为准，详细证据进入实施追踪和验收文档。
- 业务 WorkflowRun、QuestionCard、Workflow Checkpoint 和 ExternalActionCommand 是事实源；LangGraph 快照只保存可重建技术状态。
- 旧数据库表/列和旧 Item 只读保留到迁移/数据保留期结束，运行时代码不得访问或创建旧授权 QuestionCard。
- 保留 `JdkClientHttpRequestFactory`，不为宿主机 Windows/JDK loopback 问题引入 `SimpleClientHttpRequestFactory` 或项目启动兼容代码；该问题只在用户级 Java 运行环境配置中处理。
- 当前用户授权本计划创建阶段性 Git commit，不执行 push。

TODO:

- 阶段七：在当前已恢复 loopback 的环境重跑真实 `*IT`，使用数据库副本完成 V7→V8→V9 迁移核验，运行真实配置/订单夹具/前端黄金路径，补齐最终验收证据。
- 所有阶段七验收完成后，才覆盖 handoff 为 `status: completed` 快照。

Blocked:

- 本地代码门禁与 loopback selector 暂无阻塞；真实数据库副本、模型服务、HTTP `*IT` 和浏览器黄金路径尚未在本轮重新执行。

Next action:

- 先在当前运行中的 `8090` 服务完成人工黄金路径试用；随后停止服务并重跑真实 `*IT`，再执行数据库副本、真实模型/订单夹具/浏览器黄金路径验收。

Validation:

- `mvn clean '-DskipTests=false' test`：通过。
- `mvn dependency:analyze -DskipTests`：通过，Core、Infrastructure、App 均无依赖问题。
- Python convention、脚本 9 项、runtime eval 5 项：通过。
- 本轮修复后再次运行 `mvn '-DskipTests=false' test`：Core 48、Infrastructure 68、App 17，合计 133 项通过；前端 typecheck、Vitest 32 项、production build 通过；Impeccable layout detector 返回空问题集；`8090` 健康检查为 `{"groups":["liveness","readiness"],"status":"UP"}`，Vite `5173` 返回 200。
- HTTP Fake Transport 定向测试、LangGraph/QuestionCard/Checkpoint/Continuation 定向测试：通过；本轮额外覆盖提交后取消竞态、过期续跑重试、批准/拒绝 Checkpoint 事实变化和 Agent QuestionCard `runId: null`。
- 此前 `mvn verify` 的 8 项真实 `*IT` 失败已定位为同一宿主机 loopback 配置，待停止当前服务后按新环境配置复跑；前端 typecheck、Vitest 31 项和生产构建：通过。
- 2026-08-28 宿主机验证：未修改项目代码，`Selector.open()` 返回成功；执行 `mvn clean spring-boot:run -pl commerce-guardian-agent-app` 后 Tomcat、MySQL、Flyway V9 和 DeepSeek `HttpClient` 均完成装配，`GET /actuator/health` 返回 `UP`，服务继续监听 `8090` 供人工试用。

Preserve:

- 不修改或提交 `.idea`、deployment、Docker、Hook、脚本及其他未纳入当前阶段的既有工作树改动。
- `agent-fronted` 是当前唯一前端目录；历史旧 Item 和旧数据库结构只用于迁移或只读展示。
