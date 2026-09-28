# P3：迁移催发货到 Java Workflow

## 前置条件

P2 通过。核对 AgentWorkflowEngine、现有催发货策略、Checkpoint/Command 唯一性和旧版恢复实现。

## 执行

1. 保持协调端口稳定，新增持久化 Java 编排版本；按 Run 的不可变版本选择恢复实现。
2. Core 持有纯业务策略；Infrastructure 负责订单事实读取、事务和存储，不建立通用图引擎。
3. 新 Run 不依赖图快照。授权指纹只含动作、主体/订单归属、确认参数及资格判断所需事实。
4. 批准时事务外重新读取事实。授权内容变化则废弃旧批准、创建新版本确认；资格失效则收口；符合原确认才执行。
5. 事务内 CAS Run/Checkpoint 并创建唯一 Command；订单服务仍须校验写入前提。
6. QuestionCard 与 Checkpoint 保持独立。配置只影响新 Run；Legacy/V1/V2 兼容期按原语义恢复。

## 验收、回滚、交接

覆盖补参、批准、拒绝、资格/事实变化、并发、重复请求、命令事务前崩溃、Worker 重试及重启。无关展示字段变化不重新授权；影响授权的事实不能沿用旧批准；单 Run 至多一个有效命令。停用新路由后保留 Java Run 恢复能力。通过后转 P4。

## P3 执行证据（2026-09-24）

- 新增 `EXPEDITE_JAVA_V1` 持久编排版本和 Java 催发货状态机。新路由由 `AI_AGENT_EXPEDITE_MODE=JAVA` 控制；空值仍关闭，新配置缺失时兼容旧 `AI_AGENT_EXPEDITE_GRAPH_MODE`。路由只影响新 Run，恢复按 `WorkflowRun.orchestrationVersion` 选择，历史 Legacy/V1/V2 仍走原 LangGraph 实现。
- Java Run 将请求、订单和指纹事实写入 `WorkflowRun.stateJson/stepsJson`，不创建或读取图快照。QuestionCard 补充订单与 Checkpoint 批准仍是独立协议；批准时事务外重新读取订单，资格失效则安全失败，授权事实变化则废弃旧 Checkpoint 并要求再次确认。
- 本地事务内锁定并校验 Run/Checkpoint 版本，按 Run 与业务幂等键创建单一催发货命令。Java Run 使用 `HANDOFF_WORKER` 和 `VERIFY_OUTCOME` 步骤，Worker 结果落库、Items 和前端状态不依赖 continuation；P1 的旧 Run continuation 行为保持不变。
- Java 授权指纹绑定动作、用户/订单归属、订单资格状态及请求中提供的确认参数；物流轨迹、商品摘要、日期和其他展示字段不参与，避免无关事实变化触发重新授权。订单服务仍须在实际写入时核验 `PAID` 前提。
- 回归验证：Core 100 项、Infrastructure 153 项、App 25 项单测全部通过；HTTP 集成 9 项通过。随机隔离 MySQL 8.4 的 Workflow acceptance 6 项通过，覆盖编排版本往返、V9→V13 迁移、唯一命令、并发 Run CAS、Java Checkpoint 并发批准及未知结果预算。
- Java Workflow 与路由测试覆盖缺单补参、批准后唯一命令、重复批准、订单资格失效、归属变化、用户拒绝，以及新 Run 开关路由和旧 Run 按持久化版本恢复。授权指纹测试确认物流和展示字段变化不触发重新确认。
- 完整自动门禁：Python 规范检查及 27 项脚本测试通过；前端 typecheck、Vitest 77 项、production build 通过；`mvn -B -Pworkflow-acceptance '-DskipTests=false' verify` 通过；改动后的 `git diff --check` 在提交前复核。
- Java 路由仍默认关闭。本阶段未执行真实第三方订单服务、浏览器黄金路径和部署回滚；P7 负责隔离端到端及发布验收，P6 负责真实模型质量评测。

## 回滚与交接记录

- 关闭 `AI_AGENT_EXPEDITE_MODE` 可停止新建 Java Run；已经写入 `EXPEDITE_JAVA_V1` 的 Run 必须保留 Java 引擎恢复能力。回滚应用只能使用识别该枚举和新步骤的兼容构建。
- P3 代码与隔离验收通过，下一步进入 P4。提交只纳入能明确归属本阶段的文件与差异，不覆盖工作区先前阶段的未提交改动。
