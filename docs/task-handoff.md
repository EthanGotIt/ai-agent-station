status: active
updated: 2026-08-28

# Task Handoff

Goal:

- 完成 V7 整改与 Workflow 框架迁移：Spring AI 负责模型与 Tool Calling，LangGraph4j 负责确定性订单 Workflow；QuestionCard 只提问，Workflow Checkpoint 独立承载执行确认，并清理旧运行时路径。
- 当前产品裁决：Thread 不提供回收站/归档恢复入口；订单不再隐藏或恢复，只提供经 Workflow 确认的 `DELETE_ORDER` 直接删除记录。

Completed:

- 已完成 LangGraph4j 基础门禁、QuestionCard/Workflow Checkpoint 拆分、固定七节点 Workflow、Continuation 生命周期加固和前端双卡片交互。
- 已删除旧 Workflow Question/Answer Core 模型、Store、admission、failure reconciler、事务推进引擎、旧 HTTP DTO、旧控制器入口和旧授权兼容构造器。
- 运行时只读取新的 QuestionCard、Workflow Checkpoint、Question Answer 和 Workflow Decision；历史 `WORKFLOW_QUESTION`/`WORKFLOW_ANSWER` 仅保留迁移与只读投影边界。
- 已将 HTTP 协议单测改为 Fake Transport，并把真实 loopback 测试重命名为 `*IT`；Maven Surefire 只运行 `*Test`，Failsafe 负责 `*IT`。
- 已清理生产代码、迁移说明、架构/运行手册和脚本中的旧前端与旧授权运行时引用。
- 已修复规范门禁发现的 persistence 包、Clock 注入和测试命名问题；阶段六整改与该独立修复均已提交。
- 本轮代码评审已收口续跑重试的过期 Turn 快照、提交后入队取消竞态、批准后事实变化的 Checkpoint 失效/重核验、事实变化时拒绝决策的终态收口、Agent QuestionCard 的空 `runId` 前端解析、重复 Workflow Answer 类型，以及事务代理和 Jackson 决策编解码器的启动装配问题；对应修复已按独立 `fix:`/`refactor:` 提交。
- 本轮本地验收已通过 convention、脚本 10 项、runtime eval 5 项、`mvn clean '-DskipTests=false' test`（core 48、infrastructure 72、app 17）、`mvn verify` 真实 HTTP `*IT` 9 项、前端 typecheck/Vitest 35 项、production build 和无警告的 `mvn dependency:analyze -DskipTests`。
- 2026-08-28 已确认 Windows/JDK loopback pipe 失败来自宿主机 Java 临时目录继承，而非项目 HTTP 实现；项目兼容代码已完全撤销。用户级 `TEMP/TMP` 恢复为同一用户临时目录，Java 通过宿主机 `jdk.net.unixdomain.tmpdir` 使用短系统临时目录；裸 `Selector.open()` 通过，从干净编译产物启动原始应用后 `8090` 健康状态为 `UP`。
- 2026-08-28 已修复退款/催发货失败：先创建 WorkflowRun 再写 `AGENT_GRAPH_SNAPSHOT`，消除外键顺序错误；MyBatis Saver 改为不受 Spring 持久化代理的组件，由 Workflow Engine 外层事务统一管理，避免 LangGraph 内部锁被 CGLIB 代理破坏。真实退款与催发货均完成并核验，订单状态分别为 `REFUNDED` 与 `EXPEDITE_REQUESTED`；前端同时兼容 Workflow 回执的 `status` 和金额字符串，并让空 Thread 内容轨道与 Composer 对齐。
- 已完成直接删除订单记录的代码链路：`DELETE_ORDER` 贯通订单卡片、Workflow `AUTHORIZE`、本地/HTTP 网关、外部动作 Worker 和 SQLite 夹具；删除同步清理物流轨迹，重复请求按幂等键返回稳定结果，前端删除后隐藏后续订单动作并显示“记录已删除”。
- 已移除订单隐藏/恢复的生产写入口、HTTP `/visibility` 夹具端点和 Agent Tool visibility 参数；旧枚举、`HIDDEN_AT`、历史 Item 仅保留读取兼容，历史命令不会再产生隐藏状态变更。
- 已移除工作台 Thread 回收站/归档操作、后端归档保护组件和前端 archive 调用；`PATCH /threads/{threadId}` 只允许改标题，旧 `ARCHIVED` 状态仍可读取但不会被标题更新恢复，当前 UI 不提供恢复路径。
- 真实启动复核补齐 `HttpOrderGateway` 与 `HttpLogisticsGateway` 的多构造器注入标记；加载 `.env` 后后端连接 MySQL、完成 Flyway V9 并稳定监听 `8090`，订单夹具监听 `18080`，前端监听 `5173`。
- 现场删除复核使用同一幂等键连续删除夹具订单，首次与重放均返回 `ORDER_DELETED`，订单和物流查询均返回 `404`。
- 代码评审新增本地删除事务边界；因 Spring CGLIB 代理要求，`LocalOrderGateway` 改为可代理类后重新打包启动成功，避免清理物流后订单删除失败造成半删除状态。
- 代码评审进一步修复本地删除异常路径：`@Transactional` 方法捕获持久化异常时显式标记回滚，避免以失败结果返回却提交部分清理；`7bc8134` 后 Core/Infrastructure/App 测试与重新打包启动均通过。
- 最终 JAR 重启后 MySQL/Flyway V9/Tomcat 健康检查为 `200 UP`；浏览器重载确认无回收站/归档/恢复按钮、订单卡片有“删除记录”入口，控制台无 warning/error。

Decisions:

- 本文件是“最新状态快照 + 唯一下一步指针”，不累计提交列表或过程日志；历史提交以 Git 历史为准，详细证据进入实施追踪和验收文档。
- 业务 WorkflowRun、QuestionCard、Workflow Checkpoint 和 ExternalActionCommand 是事实源；LangGraph 快照只保存可重建技术状态。
- 旧数据库表/列和旧 Item 只读保留到迁移/数据保留期结束，运行时代码不得访问或创建旧授权 QuestionCard。
- 保留 `JdkClientHttpRequestFactory`，不为宿主机 Windows/JDK loopback 问题引入 `SimpleClientHttpRequestFactory` 或项目启动兼容代码；该问题只在用户级 Java 运行环境配置中处理。
- 当前用户授权本计划创建阶段性 Git commit，不执行 push。

TODO:

- 阶段七：使用数据库副本完成 V7→V8→V9 迁移核验，运行真实模型配置和前端浏览器黄金路径，补齐最终验收证据；本轮已完成浏览器界面结构复核，实际不可逆删除点击仍需人工确认后执行。
- 所有阶段七验收完成后，才覆盖 handoff 为 `status: completed` 快照；当前直接删除/无回收站改动已完成代码、夹具协议和界面入口复核。

Blocked:

- 本地代码门禁、真实 HTTP `*IT`、订单夹具和浏览器界面结构检查暂无阻塞；数据库副本、真实模型请求尚未在本轮重新执行，浏览器不可逆删除点击需用户在动作前确认。

Next action:

- 当前 `8090`、`18080`、`5173` 已运行；下一步在用户确认不可逆删除后完成浏览器黄金路径，再执行数据库副本和真实模型验收。

Validation:

- `mvn clean '-DskipTests=false' test`：通过。
- `mvn dependency:analyze -DskipTests`：通过，Core、Infrastructure、App 均无依赖问题。
- Python convention、脚本 10 项、runtime eval 5 项：通过。
- 本轮修复后 `mvn clean '-DskipTests=false' test`：Core 48、Infrastructure 72、App 17，合计 137 项通过；`mvn verify` 另通过真实 HTTP `*IT` 9 项；前端 typecheck、Vitest 35 项、production build 通过；Impeccable layout detector 返回空问题集；当前服务健康检查为 `200 UP`。
- HTTP Fake Transport 定向测试、LangGraph/QuestionCard/Checkpoint/Continuation 定向测试：通过；本轮额外覆盖提交后取消竞态、过期续跑重试、批准/拒绝 Checkpoint 事实变化和 Agent QuestionCard `runId: null`。
- 真实 HTTP `*IT` 已在本次环境配置下通过 9 项；此前的 loopback 失败不再复现。前端 typecheck、Vitest 35 项和生产构建：通过。
- 2026-08-28 宿主机验证：未修改项目代码，`Selector.open()` 返回成功；加载用户级 `.env` 后由 review 脚本启动夹具、前端和应用，Tomcat、MySQL、Flyway V9 和 DeepSeek `HttpClient` 完成装配，`GET /actuator/health` 返回 `200 UP`；直接删除订单记录及同幂等键重放均返回成功，服务当前仍在监听 `8090/18080/5173`。
- 本轮新增验证：`mvn clean '-DskipTests=false' test`、`mvn verify`（真实 HTTP `*IT` 9 项）、前端 typecheck/Vitest 35 项/build、Python convention/10 项脚本/runtime eval、dependency analyze 均通过；Impeccable detector 返回空问题集；最终服务重启成功且浏览器结构检查无归档入口、无控制台错误。
- 事务回滚修复后再次运行 `mvn -q test` 与 Infrastructure 定向测试通过；使用最新 JAR 重启后 `GET /actuator/health` 返回 200/UP，`8090`、`18080`、`5173` 持续监听。

Preserve:

- 不修改或提交 `.idea`、deployment、Docker、Hook、脚本及其他未纳入当前阶段的既有工作树改动。
- `agent-fronted` 是当前唯一前端目录；历史旧 Item 和旧数据库结构只用于迁移或只读展示。
