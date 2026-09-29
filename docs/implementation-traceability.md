# Commerce Guardian Agent 实现追踪矩阵

> 状态：`active`
> 更新日期：2026-09-29
> 目标来源：当前唯一前向计划 [长期调整计划](upgrade-plan.md)、当前工作树、架构文档、SQL、测试和实际运行结果。

本矩阵只把代码、测试和运行结果作为证据。原计划或 [任务交接快照](../.codex/task-handoff.md) 中的“已完成”描述不能单独作为完成证据。

> 计划边界说明：本文件记录多条工作线的实施证据。下方历史阶段编号各自保留，不与新的 P0—P7 计划编号合并计算。

## 当前长期升级计划

既有 Items、上下文、LangGraph V1/V2、Worker 和 Live driver 结果继续作为历史证据。前向实施以 `docs/upgrade-plan.md` 的 P0—P8 及各阶段执行卡为准。P0—P7 的历史执行证据不代表 P8 新执行语义已经验收；以下旧表格是此前状态快照，不构成继续扩展 LangGraph 的目标。

### P8 计划基线（2026-09-29）

- 决策：保留 Thread → Turn → Item；`WorkflowRun → WorkflowTask` 仅改内部名称和显式边界映射，不改变业务身份、数据库列或公开兼容字段。补参、批准、Steer 和 Worker 结果继续使用不同协议；同一逻辑 Turn 可持久化暂停并恢复原 Agent 循环。
- 当前与目标差异：当前 QuestionCard/Checkpoint 输入仍排入新的 Turn，Workflow 启动后截断工具循环，Worker 结算后不恢复 Agent。目标行为尚未实现，不能将此计划记录为交付事实。
- 本工作区基线重跑：规范检查、Python 31 项、前端 typecheck、Vitest 77 项、build、`git diff --check` 通过；`mvn clean test` 被预存未跟踪的 `infrastructure/.../workflow/langgraph` 源码阻断，这些源码引用 P5 已移除的 LangGraph4j API。未跟踪文件保留原样。P5/P7 干净提交的 Maven 通过记录仍作为历史证据。
- 尚未重跑：MySQL acceptance profiles、隔离订单服务、Live 模型、目标库只读盘点、回滚演练和真实浏览器 SSE/响应式验收。

### P8 阶段 1—2：计划基线与 WorkflowTask 命名（2026-09-29）

- P0 校准已单独提交并推送（`6e81420`）：总体计划、产品/架构入口和 P8 执行卡统一为“原 Turn 暂停并恢复”的目标语义，同时明确当前运行时仍会结束工具循环；既有 P5—P7 未完成环境验收继续单独追踪。
- P1 将 Core 模型、Store、owner-recovery 类型及 MyBatis Entity/Mapper/Store 内部更名为 WorkflowTask；Turn 内部关联使用 `workflowTaskId`。没有新增数据库表或迁移，数据库继续使用 `AGENT_WORKFLOW_RUN.RUN_ID` 与 `AGENT_TURN.WORKFLOW_RUN_ID`，HTTP `runId`/`workflowRunId` 继续作为兼容字段。
- 自定义 MyBatis 查询显式将 `RUN_ID` 映射为 `TASK_ID`；回归测试验证旧表/列映射和查询别名。对外 QuestionCard、Checkpoint、Command、Item 与接受响应契约未重命名。
- 验证：Core 102 项、Infrastructure 131 项、App 25 项单测通过；Maven reactor 使用 `mvn -pl commerce-guardian-agent-app -am clean -DskipTests=false test`。为避免预存未跟踪且引用已删除 LangGraph4j 的旧源码阻断编译，测试期间仅临时移出对应的未跟踪 LangGraph 源目录，结束后已原样恢复；常规包含这些目录的 `mvn clean test` 仍不能作为通过证据。
- 隔离 MySQL acceptance：`AgentItemStoreMySqlIT` 6 项与 `AgentWorkflowMySqlIT` 7 项通过；Workflow 测试从 v9 增量迁移至 v14，并覆盖 `WorkflowTask.taskId`、旧表/列、自定义查询映射及编排版本持久化。该结果验证命名兼容，不替代后续新 Turn 暂停恢复与兼容版本回滚验收。

### P0 基线（2026-09-24）

| 范围 | 结果 | 证据 |
| --- | --- | --- |
| Python 规范与脚本 | 通过 | convention_check；26 项 unittest |
| Java 单测 | 通过 | clean test；Core 97、Infrastructure 139、App 25，共 261 项 |
| MySQL acceptance | 通过 | MySQL 8.4 随机库；Items 6 项、Workflow 3 项，共 9 项；随机库清理成功 |
| 前端 | 通过 | typecheck；Vitest 76 项；production build |
| Diff 检查 | 通过 | git diff --check |
| Live/浏览器/发布 | 未执行 | 属于后续 P6/P7，真实服务和浏览器验收不由本次自动化基线替代 |

### P1 结算与续跑解耦（2026-09-24）

- Worker 现在无论是否装配 continuation gateway，都会持久化 Workflow 结果及 VERIFY_OUTCOME 事实；只有 continuation 实际新建时才记录 HANDOFF_AGENT。
- WorkflowResult 状态从命令结果和核验事实确定：已核验为 COMPLETED，已受理待核验为 APPROVED，重试等待和人工处理保持各自状态。EXTERNAL_ACTION_STATUS 仍是执行回执的权威来源。
- Legacy/V1/V2 的 continuation 策略继续从 Run 持久化编排版本读取；后续 Java 编排版本采用确定性收尾，运行时配置不重解释旧 Run。
- 验证：ExternalActionWorkerTest 7 项；Infrastructure 全量 140 项及 Core 97 项通过。未运行 App/MySQL acceptance；本阶段改动集中在 Infrastructure Worker 投影。

### P2 未知外部结果核验（2026-09-24）

- `ExternalActionCommandModel` 将动作结果、明确失败重试预算和未知结果核验预算分开；核验耗尽保持 `UNKNOWN` 并进入 `MANUAL_VERIFICATION_REQUIRED`，人工恢复保留原命令及幂等身份。
- Worker 将超时、连接中断、5xx 和本地动作回执写入不确定归为待核验；HTTP 408 同样待核验。外部动作状态、Workflow 结果、Turn 状态、Items、上下文及前端显示已加入新状态。
- 当前订单服务没有按幂等键独立查询动作回执的 API；夹具证明了同一 `Idempotency-Key` 重放可返回缓存结果，`COMMIT_THEN_ERROR` 注入确认远端只发生一次业务变更。该证据仅覆盖本地夹具服务端契约。
- 删除成功仅认明确删除回执，不以一次 `NOT_FOUND` 查询作为成功证据。退款、催发货和删除均不会因未知结果创建新的命令身份。
- 数据库 V13 增加 `OUTCOME_STATUS` 和独立核验预算；修正基线列归属及 V9 测试夹具，使旧结构增量迁移可验证。`AgentItemStoreMySqlIT` 与 `AgentWorkflowMySqlIT` 在隔离 MySQL 8.4 分别 6、4 项通过，版本升至 V13。
- 验证：Core 99/99、Infrastructure 145/145、HTTP 集成 9/9、App 单测 25/25；最终改动后单独执行两套 MySQL acceptance 10/10。Python 27 项、前端 typecheck、Vitest 77 项、production build、规范检查及 diff 检查通过。完整 reactor 首次发现的迁移夹具及自定义 MyBatis 字段映射问题均已修复并通过针对性 MySQL 验收。
- 浏览器、真实模型和第三方订单服务幂等契约不属于本阶段已验证范围，分别由 P6/P7 计划覆盖。

### P3 催发货 Java Workflow（2026-09-24）

- 新催发货 Run 可按 `AI_AGENT_EXPEDITE_MODE=JAVA` 创建 `EXPEDITE_JAVA_V1`，并以 WorkflowRun 持久化步骤和业务状态恢复；新 Run 开关不改写历史 Run 的版本语义。V1/V2/Legacy 仍由兼容引擎读取。
- Java 核心策略核验 PAID 资格与用户归属；授权指纹仅包含动作、用户/订单身份、资格状态和请求确认参数。确认字段变化需重新授权，资格或归属失效则结束且不创建命令。QuestionCard 补参与 Checkpoint 授权仍独立。
- 批准事务锁定并校验 Run/Checkpoint 版本后创建唯一 ExternalActionCommand；Worker 结果投影按 Java Run 确定性结束，不依赖 Agent continuation。配置只影响新 Run。
- 验证：Java Workflow 5 项与版本路由 3 项定向测试通过；Core 100/100、Infrastructure 153/153、App 25/25 单测通过，HTTP 集成 9/9 通过。隔离 MySQL 8.4 Workflow acceptance 6/6 通过，覆盖编排版本往返、V9→V13 迁移、CAS、命令幂等和未知结果预算。
- 完整门禁还包括 Python 27 项、规范检查、前端 typecheck/Vitest 77/build，均通过。未启用真实订单服务、未做浏览器黄金路径或部署回滚演练；这些分别归入 P7/P6，不视为 P3 代码验收的替代。

### P4 退款、删除与运行时衔接（2026-09-28）

- 实现：新增 `REFUND_JAVA_V1`、`DELETE_JAVA_V1` 及版本路由；退款原因补参、退款金额/币种与删除范围分别纳入授权指纹。三类 Java Workflow 均在批准时重新读取订单事实，并在事务内通过版本校验创建唯一 ExternalActionCommand。
- 并发与恢复：新增 `AGENT_ORDER_WRITE_RESERVATION`，按用户/订单阻止未完成写事项跨 Thread 冲突；Worker 在成功核验后释放预留，取消、拒绝和核验失败路径也释放。已有 Java Run 按持久化版本恢复，不依赖部署时开关猜测。
- 运行时：Workflow Tool 成功启动后沿用协调层批次截断；Worker 结果投影不创建 Java continuation。普通后续 Turn 注入最近 Workflow/Command 事实，明确“命令受理不等于业务成功”。
- 验证：定向 18 项、Core 102/102、Infrastructure 164/164、App 25/25 单测通过；HTTP 集成 9/9、隔离 MySQL acceptance 13/13 通过；Maven `context-acceptance,workflow-acceptance verify` 成功。真实模型、浏览器和部署回滚仍待 P6/P7。

### P5 排空旧流程并退出 LangGraph4j（2026-09-28）

- 只读盘点：新增 [`scripts/maintenance/workflow-inventory.sql`](../scripts/maintenance/workflow-inventory.sql)，按编排版本统计 Run，单独列出旧非终态 Run、开放 QuestionCard/Checkpoint、未结算命令、待消费 continuation 和图快照；`scripts/tests/test_workflow_inventory.py` 验证脚本没有写入、DDL 或锁表语句，并覆盖全部盘点表。
- 生产路径：新增 `RetiredAgentWorkflowEngine`，旧持久化编排恢复以 `WORKFLOW_COMPATIBILITY_REQUIRED` 受控拒绝，避免缺少兼容运行时后按 Java 语义猜测执行；`VersionedAgentWorkflowEngine` 新 Run 仅路由 Java 编排，`ExternalActionOutcomeManager` 不再创建 continuation。
- 依赖与快照：移除 Maven 的 LangGraph4j 依赖、图引擎/节点、技术快照 Entity/Mapper 和生产 continuation gateway；V8/V12 迁移、`AGENT_GRAPH_SNAPSHOT`、`CONTINUATION_JSON`、历史 Item/Turn 类型和编排枚举保留，支持读取、排空和回滚。
- 配置与文档：移除旧图模式、自动 continuation 配置入口，更新架构、运行手册和执行卡；P5 提交不包含工作区中既有 V2、前端和评测改动。
- 限制：当前执行环境没有目标 MySQL 的只读凭据，未对真实目标库宣称旧记录已归零。部署前必须执行盘点脚本并保存结果，确认旧服务/Worker/队列退出后才可关闭旧准入。

### P6 上下文保留与真实模型评测工具（2026-09-28）

- 评测工具：Live driver 保留 Items、水位、预算和压缩机制，新增同一 Thread 的 6 类长对话场景；Workflow 收口后通过普通后续 Turn 验证最新业务事实，不依赖自动 continuation。
- 评测边界：报告只写场景编号、轮数、决策、Item 类型、交互数、变更数、终止状态、耗时、断言和上下文指标；Token 用量缺失写为 `unavailable`，不写 Prompt、Thinking、原始响应、密钥或请求头。
- 验收门禁：每套既定模型执行 12 个基线场景 × 3 次和 6 个长对话场景 × 3 次；基线安全 36/36、路由至少 35/36，长对话安全 18/18、断言至少 17/18，业务事实必须可追溯。
- 离线证据：`python -m unittest discover -s scripts/tests -p "test_*.py"` 31 项通过，规范检查和 diff 检查通过；确定性替身的 36/36 结果仅验证评测脚本，不替代真实模型证据。
- 阻塞：本次执行环境没有 `DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL`、`DEEPSEEK_BASE_URL`，未运行真实 Live；取得凭据并启动隔离 Agent/订单夹具后才可完成模型选择和 P6 验收。

| 原始阶段 | 当前结论 | 代码与验收对照 | 仍需推进 |
| --- | --- | --- | --- |
| 1. 运行时硬错误 | 已完成并验证 | ExternalActionCommand 的版本、Lease CAS、重试周期、事务收敛和幂等回放已有 Core/Infrastructure/MySQL 证据 | 无功能缺口 |
| 2. 类型化 Item 协议 | 阶段 1 本地与 MySQL 跨层验收通过 | `JacksonAgentItemPayloadCodec` 统一生成/校验 envelope；Core `AgentItemJournal` 与 Infrastructure `TransactionalAgentItemJournal` 集中处理序号、幂等追加和提交后事件；`AgentItemPayloadValue` 覆盖 18 类公开 payload；`AgentItemStoreMySqlIT` 使用真实 MySQL 8.4、MyBatis Store、Codec、Journal 和 Controller 验证 601 条 Item 按 200 分页恢复、18 类 typed `data`、跨调用 `appendIfAbsent` 同一 Sequence 和单次事件发布；Controller 与前端均对重复、乱序、陈旧/空页安全收口；完整 reactor 261 项、Python 26 项和前端 76 项通过 | `AgentItemModel` 继续以兼容字符串保存历史 JSON；未知 kind、旧 envelope 和纯文本 fallback 按计划保留，需在目标环境补刷新后 SSE 的现场证据 |
| 3. Turn、HITL 与 Context 恢复 | 行为已完成，结构仍需收口 | Question/Checkpoint CAS、回答重启恢复、固定水位、摘要/快照 CAS、溢出恢复和事项级 Continuation 均有证据 | `AgentTurnRuntimeService` 仍是聚合实现；类型化 Item 收口后需补一次跨层恢复矩阵 |
| 4. DeepSeek Coordinator | 基线保持 Pro，Live 对比待执行 | Spring AI Tool Calling、取消、超时、流式 SSE、错误分类和 Thinking 隔离已有现场证据；`DEEPSEEK_MODEL`、`DEEPSEEK_THINKING_TYPE` 和 `DEEPSEEK_REASONING_EFFORT` 已可由环境覆盖 | 需用 Live driver 完成两套配置各 12×3 场景，并按长期计划定案模型 |
| 5. Workflow 与 Worker | V1 技术试点完成，V2 本地价值与恢复门禁已通过主要代码测试 | `EXPEDITE_GRAPH_V1` 和 `LEGACY_V1` 保持历史恢复；新增 `EXPEDITE_GRAPH_V2`、Core `ExpediteWorkflowPolicy`、typed 图节点和 `OFF|V1|V2` 路由；V2 无中断重建图可在快照缺失时重建批准路径并只产出确定性草稿；Worker 结算、命令去重、事实变化、未知版本和低基数指标均有回归；退款/删除仍保留旧路径 | 仍需在目标环境补多进程并发批准、真实外部回执失败重放、V2 显式启用/回滚和第三方鉴权现场证据 |
| 6. SSE、API 与前端工作区 | 大部分完成 | SSE 缓冲/补拉/有序发送、执行回放、线程切换保护、QuestionCard 和结果优先级已有单测及浏览器证据 | 直接删除动作、剩余视口和真正浏览器 E2E 仍需按发布矩阵复核；当前工作区前端设计/文案改动需单独验收 |
| 7. 配置、代码与文档清理 | 进行中 | 已新增长期计划入口、V2 模式、thinking/reasoning 环境覆盖、Live driver 和一次性夹具合成订单入口；夹具支持按合成订单注入不可重试/临时故障；`EXTERNAL_ACTION_COMMAND` 已从公开 Item 评测类型中移除，改由 `EXTERNAL_ACTION_STATUS.commandId` 关联；旧配置继续兼容 | 当前 Agent/订单夹具未运行且进程没有 DeepSeek 凭据；需完成两套 Live 结果、模型定案和发布回滚证据 |

以上各行为此前实施路线的历史快照。当前唯一前向路线是 [总体调整计划](upgrade-plan.md) 中的 P0—P7；Live 评测归入 P6，交付、浏览器和回滚证据归入 P7。

## 第一阶段运行闭环加固（2026-09-04）

| 验收面 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 终止与同批截断 | 已通过本地门禁 | `ControlledToolCallingAdvisor` 显式装配唯一 Tool Calling 循环；`ControlledToolCallingManagerTest.stopsRemainingBatchAfterFinish` 覆盖 FINISH 后不执行后续工具；Coordinator 测试覆盖终止消息优先；完整 `mvn clean test` 通过 | 真实模型黄金路径仍需现场复核 |
| 输出与上下文预算 | 已通过本地门禁 | `AgentExecutionContextTest` 覆盖预留、缺失/零 usage、幂等结算和上下文超限；Coordinator 测试覆盖真实 Advisor 路径的缺失 usage、已知 usage、断流保守结算和输出额度耗尽时终止工具优先；Runtime 测试覆盖 SSE 发布失败不改写已提交 Turn | 真实模型黄金路径仍需现场复核 |
| 工具失败熔断与结果边界 | 已通过本地门禁 | `AgentToolFailureCircuitBreaker` 按工具、规范化参数和稳定错误码计数；`ControlledToolCallingManagerTest` 覆盖三次重复失败、成功重置和统一结果截断；前端测试覆盖具体停止原因 | 真实模型黄金路径仍需现场复核 |
| 长上下文起点 | 2A-1 已完成，2A-2 已接入本地实现 | `AgentContextAssembler` 固定最大 Sequence，按 300 条分页连续读取原始 Items；2A-2 增加 80% 压力裁剪、16% 尾部保留、完整 Turn/Tool 批次摘要、V2 快照 CAS 和溢出恢复边界；Core/Infrastructure 定向测试覆盖旧摘要忽略、固定水位、严格游标、取消、工具配对和预算停止 | 真实 DeepSeek 压缩质量、V10 现场迁移和浏览器矩阵仍需单独验收 |

## 2A-1 上下文基础巩固（2026-09-05）

| 验收面 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 固定水位与连续读取 | 已通过本地及 MySQL 集成门禁 | `AgentItemStore.captureWatermark/listItemsThrough`、MyBatis 有界查询和 `AgentContextAssembler` 页面完整性校验；`AgentItemStoreMySqlIT` 在真实 MySQL 8.4 中验证 601 条历史、多页读取、水位后追加、用户归属和新会话读取 | 测试数据库由本次临时验收创建；生产库仍需按运行手册单独复核 |
| 上下文视图边界 | 已通过运行时跨层门禁 | Runtime 测试覆盖超过 300 条历史下确定性订单动作跳过组装；纠正调用测试在首轮持久化 Tool Call/Result 后确认第二轮重新带回配对事实；Core/Coordinator 测试覆盖当前输入、排队隔离、孤立事实和真实 Advisor 链 | 2A-2 才加入摘要替换和重启恢复 |
| 请求预算与结果边界 | 已通过本地及真实 Advisor 链门禁 | `AgentContextTokenEstimator` 统一字符估算；Advisor 将实际 `maxTokens` 限制为请求预留，按请求标识结算并处理缺失 usage、断流、迟到响应；工具结果按最终 JSON 长度保留关联字段 | 真实供应商模型仍属于后续模型验收 |
| 观测与前端停止原因 | 已完成本地门禁及 2A-1 浏览器黄金路径 | Context Item 记录读取水位、覆盖范围、条数、完整性、当前/峰值估算；Micrometer 记录输出预留/结算；前端识别 `CONTEXT_HISTORY_INVALID`、`CONTEXT_BUDGET_EXCEEDED` 和 `OUTPUT_BUDGET_EXCEEDED`；真实 Agent + 独立订单夹具已验证查询、催发货拒绝/批准、重试完成和刷新恢复 | 深浅主题、reduced-motion、SSE 重连和错误焦点仍按完整矩阵补录 |

## 2A-2 Harness 式自动上下文压缩（2026-09-05）

| 验收面 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 压力顺序与结构化视图 | 已接入并有 Core 回归 | `AgentModelContext` 分离摘要、原始事实、水位和视图版本；`AgentContextAssembler` 按“完整估算 → Tool Result 裁剪 → 连续完整前缀摘要 → 严格缩减校验”执行，裁剪后解除压力时不调用摘要 | 真实流式 Tool 批次和供应商上下文窗口仍需验收 |
| V2 快照与恢复 | 已通过本地及 MySQL acceptance 门禁 | V10 增量迁移、MyBatis Thread 锁/CAS、V1 忽略、V2 元数据映射；CAS 失败读取胜出快照，不重复调用摘要，原始 Items 与 SSE 序号不变；随机临时库验证 V9→V10、V2 写入和归属隔离 | 生产库迁移、进程重启后的现场复核和真实模型仍属后续验收 |
| 预算与溢出重试 | 已接入并通过编译/单测 | 摘要通过无 Tool `ChatModel`，共享 `AgentExecutionContext` 截止时间和 8,192 输出额度；`AgentModelContextOverflowException` 只在明确上下文错误时进入配置次数的严格缩减重试 | DeepSeek 真实错误响应、断流、缺失 usage、取消和摘要持久化失败仍需真实模型验收 |
| 观测与事实安全 | 已接入 | Context Item 记录水位、覆盖范围、估算、裁剪数量和压缩状态；Micrometer 增加低基数压缩前后估算；不保存 Prompt、Thinking、摘要正文或敏感原文 | 完整浏览器停止原因和压缩后约束保留需后续现场验收 |

## 历史 2B-1 LangGraph V1 催发货试点（技术试点完成，默认关闭）

| 验收面 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 图路由与编排版本 | 已完成，默认关闭 | `LangGraphAgentWorkflowEngine` 仅为明确订单号的催发货新 Run 选择 `EXPEDITE_GRAPH_V1`；历史、补选订单和其他动作保持 `LEGACY_V1`；V11/V12 迁移、Run/图快照版本读取和未知版本拒绝由 `MybatisAgentWorkflowTaskStoreVersionTest` 与两个 Workflow MySQL IT 覆盖；未知版本现在以 `UNKNOWN_WORKFLOW_ORCHESTRATION_VERSION` 受控失败 | 生产开关和第三方鉴权仍需按部署环境单独启用 |
| 节点、确认与命令边界 | 技术路径已通过，业务节点职责不足 | V1 的通用 Action 只维护节点名和业务阶段；批准事务锁读 Run/Checkpoint 并只创建唯一 `ExternalActionCommand`；确认前无外部写入 | 由 V2 Core 策略和 typed 节点承担真实资格、重新核验、命令草稿和 Worker 交接职责 |
| 技术快照恢复 | 已通过一次性副本现场验收 | `MybatisLangGraphCheckpointSaver` 保存节点、状态、Workflow 版本、事实指纹和编排版本；删除、损坏 `STATE_JSON`、版本失配和跨进程重启均从 Run/Checkpoint/订单事实重建，不以快照授予授权；`safeLogistics` 使用有序结构，事实指纹跨 JVM 稳定 | 生产副本和真实第三方订单平台仍按部署环境验收 |
| Worker 结果闭环 | 已通过本地、HTTP 和真实模型黄金路径 | Worker 以 PENDING/RETRY_WAIT/PROCESSING Lease 领取命令，结果写入 `EXTERNAL_ACTION_STATUS`/Workflow 事实；三次失败后人工恢复与零失败成功路径均验证同一幂等键只产生一次业务变更；成功结果已接回模型续接总结 | 无本轮代码阻塞 |

## 当前 2B-3 LangGraph V2 实施状态（默认关闭）

| 验收面 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 编排版本与开关 | 已实现，未启用 | `AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V2`、`AI_AGENT_EXPEDITE_GRAPH_MODE`；缺失新配置时兼容旧布尔开关，默认 `OFF` | 目标环境显式启用前需完成完整门禁 |
| Core 决策契约 | 已实现并有单元测试 | `ExpediteWorkflowPolicy` 集中资格判断、`expedite-facts-v2:` 指纹、确认草稿和确定性幂等命令草稿；`ExpediteWorkflowPolicyTest` 通过 | 需补事实查询失败和并发批准的跨层证据 |
| 图节点与分支 | 已实现并有拓扑/分支/重建测试 | `RESOLVE_ORDER → VERIFY_FACTS → PREPARE_CONFIRMATION → interrupt`，批准后 `REVERIFY_FACTS` 按变化结果进入确认、结束或命令构建，最终 `HANDOFF_WORKER`；快照丢失时无中断恢复图可继续到 `HANDOFF_WORKER` | 目标环境仍需补持久快照损坏、版本失配和 Worker 结算现场证据 |
| Engine 落库边界 | 已接入并通过本地门禁 | V2 Engine 使用图输出的确定性命令草稿并在本地事务内 `createIfAbsent`；批准前不创建 Command；V2 技术 END 不伪造 Worker 结果；重复命令通过命令 ID 观测去重 | 仍需目标环境补多进程并发批准和真实外部回执失败重放 |
| 低基数指标与 Live 工具 | 指标门禁已通过，真实对比未执行 | `AgentRuntimeMetrics`/Micrometer 增加编排版本、恢复/重建结果、事实结果、命令去重和 Worker 结算观测；`MicrometerAgentRuntimeMetricsTest` 断言标签只包含低基数值；`scripts/runtime_eval/live_driver.py` 通过 HTTP 自动推进交互、重试和幂等重放并只写安全字段；订单夹具支持 `LIVE-EVAL-*` 合成订单及按订单故障注入 | 当前 Agent/订单夹具未运行且没有 DeepSeek 凭据；需产生两套各 36 次结果并按规则选择模型 |

## 2B-2 生产快照现场与真实模型闭环（隔离现场已完成，生产默认仍关闭）

| 验收面 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 删除/损坏/版本失配 | 已通过 | 隔离 `AcceptanceData` 开启 `AI_AGENT_EXPEDITE_GRAPH_ENABLED=true`；Run A 删除快照后重启无 `FACTS_CHANGED`，Run B 损坏 `STATE_JSON`、Run C 快照版本失配均从业务事实重建；原始快照坏行不被重新授权 | 不把一次性副本证据表述为生产库迁移 |
| 未知编排版本 | 已通过受控失败 | Run D 将 `AGENT_WORKFLOW_RUN.ORCHESTRATION_VERSION` 改为未知值，批准 Turn 失败并持久化 `UNKNOWN_WORKFLOW_ORCHESTRATION_VERSION`，没有 ExternalActionCommand；Store 与 Runtime 回归均覆盖稳定错误码 | 无 |
| 真实模型黄金路径 | 已通过 | 干净合成订单夹具完成查询、催发货、批准、Worker、结果核验和模型续接；Run `COMPLETED`，命令 attempt 1 成功，订单物流为 `EXPEDITE_REQUESTED`，`idempotencyRecords=1`、`businessMutations=1` | 第三方平台鉴权仍需部署环境验收 |
| 浏览器 SSE 与错误焦点 | 已通过专项现场复核 | `1536×730` 会话离线/在线后重新请求 `events?afterSequence=18`；刷新先取 Items 再按游标订阅；QuestionCard 空提交后必填框为 `active + invalid`，页面有 `role=alert`；控制台 errors/warnings 均为 0 | 其他四尺寸的专项断线/焦点证据仍按运行手册逐项补录 |

## 第三阶段事项级恢复（2026-09-10）

| 验收面 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| root/parent/cycle 归属 | 已完成并已提交 | `86fcbf5`；`TransactionalAgentContinuationGateway` 从 Workflow owner 或父续跑事实恢复 `rootTurnId`，以 owner/parent 最大轮次递增，人工回答/审批子 Turn 不独立消耗次数 | 现场重启恢复仍需与 2B-2 黄金路径合并复核 |
| 后台重试与唯一恢复 | 已通过定向测试 | `RETRY_WAIT` 只等待 Worker；正常恢复和 `STOP_LIMIT` 使用确定性幂等键，Item/Turn 并发写入受 Thread 锁保护；重复恢复不创建第二个 Turn，超限停止不重复写 Item | 无代码阻塞 |
| 无法关联事项的安全收口 | 已完成 | 缺少 Workflow owner、父续跑锚点或有效关联事实时不创建恢复 Turn；原始业务事实保留，用户可主动发起新请求；Gateway/TurnStore 测试覆盖跨 Run 和缺失 owner | 无代码阻塞 |

## 第四阶段结果展示与交付（2026-09-10）

| 验收面 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 动作状态展示 | 已通过前端回归 | `OrderActionProjection`/`OrderActionStatus` 区分确认等待、PENDING、PROCESSING、RETRY_WAIT、SUCCEEDED、成功待核验和 MANUAL_RETRY_REQUIRED；新增 `OrderActionStatus.test.tsx` 覆盖 8 项 | 无代码阻塞 |
| 业务结果优先级 | 已通过投影测试和已执行浏览器路径 | `EXTERNAL_ACTION_STATUS=SUCCEEDED` 在投影末端优先于后续技术 `TURN_STATE=FAILED` 或续接错误；待核验只提供 `REFRESH_ORDER`，人工重试沿用原 Run/命令 API；`threadProjection.test.ts`、`App.test.tsx` 覆盖失败/刷新/竞态，合成订单真实模型路径已完成刷新恢复 | 当前工作区新增前端设计/文案改动需单独验收 |
| 真实验收与恢复 | 已完成本轮范围 | HTTP acceptance runner 已通过 Thread/Turn/Item、交互、幂等、执行回放、物流、退款和催发货重试；合成订单 DeepSeek 完成查询→催发货→批准→Worker→结果→总结，刷新恢复、快照故障注入和 `1536×730` SSE/错误焦点均有现场证据 | 生产开关、第三方鉴权、删除动作和其他尺寸专项证据按部署环境继续执行 |

## 2A 阻断修复（2026-09-05）

| 验收面 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 连续历史与摘要边界 | 已修复并通过 Core 回归 | `AgentModelContext.compactionUnits` 统一完整 Turn/Tool 批次边界；`AgentContextAssembler` 对当前请求、排队输入、孤立工具事实和未完成单元设置覆盖屏障，并拒绝固定范围内的 Sequence 缺口；历史尾部按完整单元保留 16%，当前请求单独渲染；`AgentContextAssemblerTest` 覆盖排队输入水位和连续缺口 | 真实 DeepSeek 长历史质量仍需现场验收 |
| V2 摘要链与 CAS 基线 | 已修复并通过临时 MySQL acceptance | 新摘要使用 `context-summary-v2`；无效最新快照只作为 CAS 预期标识，不作为基础链；`AgentItemStoreMySqlIT` 的竞争测试改用 Spring 事务代理、`SqlSessionTemplate` 和真实 MyBatis Store，V9→V10 迁移查询改用 `INFORMATION_SCHEMA` | 应用重启后的生产副本复核仍需按运行手册执行 |
| 压力与溢出恢复 | 已修复并通过 Core/Infrastructure 回归 | Advisor 复用 `AgentPromptMeasurement` 的完整 Prompt 估算；请求前无缩减但未超过硬预算时继续发送；供应商溢出只有严格缩减并改变视图后才消耗 Turn 共享重试；摘要流在批准输出额度到达时停止聚合；压力裁剪保留既有 `truncated` 标记 | 真实供应商错误码和多批次流式验收仍待现场执行 |
| 结果观测 | 已补充分项计数 | `AgentContextBudgetReport.pressurePrunedToolResults` 与 `droppedItems` 分离，`CONTEXT_ASSEMBLED` 只记录计数、范围、估算和版本，不记录 Prompt、摘要正文或 Thinking | 浏览器错误焦点和 SSE 重连矩阵仍待补录 |

## Week 4 演示验收追踪（历史快照；当前结论以 2B-2 小节为准）

| 验收面 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 本地 acceptance runner | 真实 Agent 与夹具 HTTP 完整验收通过 | PR #5 / `b002922`、基线合并提交 `f739203` 的 `scripts/acceptance/runner.py` 检查 Item 游标、刷新恢复、开放交互唯一性、Turn 幂等、执行回放；2026-09-05 在真实 Agent `8090` + 独立 SQLite 夹具 `18080` 实测通过 `thread-list`、`thread-create`、`item-recovery`、`interaction-uniqueness`、`refresh-recovery`、`turn-accepted`、`turn-idempotency`、`execution-replay`、`logistics`、`refund-idempotency`、`expedite-retry`、`delete-gated`；`scripts/tests/test_acceptance.py` 5 个单测覆盖重放、游标拒绝和删除开关 | 第三方生产订单平台鉴权仍按部署环境验收 |
| 第 2 周 36 次质量基线 | 确定性基线 36/36 安全、36/36 路由 | 已合入集成的 `codex/agent-quality-eval@d858d45` 执行 `python -m scripts.runtime_eval --repetitions 3`；该 runner 不连接真实模型 | 当前环境没有 `DEEPSEEK_API_KEY`/真实模型服务，真实模型 36 次需在本机凭据可用后重跑 |
| 浏览器验收矩阵 | 已通过已执行专项，其他视口按部署矩阵补录 | 2026-09-10 Playwright 已完成合成订单查询、催发货黄金路径、刷新恢复、四尺寸布局 smoke，以及 `1536×730` SSE 重订阅和 QuestionCard 错误焦点；此前验收还覆盖移动抽屉/Escape 和控制台无错误 | 其他视口的断线/焦点专项仍按运行手册逐项补录 |
| V7→V8→V9→V10→V11→V12 一次性副本 | V12 已通过自动化编译与迁移场景 | `AgentItemStoreMySqlIT` 与 `AgentWorkflowMySqlIT` 在随机临时库以 V9 基线执行 V10、V11、V12，验证旧数据保持、V2 快照写入、Run/图快照编排版本读取、归属隔离和 CAS；2B-2 快照故障矩阵另在隔离 `AcceptanceData` 完成；生产库迁移仍按运行手册单独执行 | 不把一次性副本证据表述为生产库迁移 |
| 本地门禁 | Java、Python、MySQL acceptance 和前端门禁已通过；LangGraph 当前定向回归 27/27 | 既有完整 reactor、Python 19 项、前端 typecheck/Vitest/build 和 2026-09-12 LangGraph 27 项回归均通过；当前回归包含引擎 21、图工厂 4、MyBatis Saver 2 | 生产开关、第三方鉴权、删除动作和未执行视口专项仍按部署环境验收 |

## 第三周：显式 Agent 决策收口（已实现，PR #4 已合入）

| 计划项 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| `complete_agent_cycle` 仅允许 `FINISH` | 已完成 | `SpringAiAgentTurnCoordinator.ControlTools` 拒绝其它 outcome；`SpringAiAgentToolBoundaryTest.completeAgentCycleAcceptsOnlyFinish` 通过 | PR #4 已合入，真实模型黄金路径需重新跑 |
| `ASK_USER` / `START_WORKFLOW` 受结构化事实约束 | 已完成 | `request_user_input` 先持久化 Agent QuestionCard；Workflow Tool 才能返回 `START_WORKFLOW`；Runtime 校验 QuestionCard、Checkpoint、Run 和等待状态 | 真实模型黄金路径需重新跑 |
| 终止消息与自由文本收口 | 已完成 | `SpringAiAgentTurnCoordinatorTest.usesControlledTerminalMessageWhenModelAddsTextAfterFinishTool` 通过，Tool 消息优先且追加文本被忽略 | 真实模型结果纳入本地质量报告 |
| 缺失决策纠正与安全失败 | 已完成 | Runtime 最多一次纠正调用；`AgentTurnRuntimeServiceTest` 覆盖纠正成功、二次缺失和非法 Workflow 决策不重复调用；失败码为 `AGENT_DECISION_MISSING` | 不进入稳定 CI 的真实模型门禁 |
| 前端重试投影 | 已完成 | `threadProjection.test.ts` 与 `App.test.tsx` 覆盖错误码投影、无开放交互、仅新 Turn/requestId 重试；`npm` typecheck/Vitest/build 通过 | 真实浏览器再次确认 |

## V7 整改与 Workflow 框架迁移追踪（代码与隔离验收已完成）

| 阶段 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 1. LangGraph4j 基础门禁 | 已完成 | `d74bc79`；`langgraph4j-core:1.8.20`、`AGENT_GRAPH_SNAPSHOT` V8、Jackson 3 序列化、MyBatis Saver 和七节点图测试；Core/Infrastructure 编译、LangGraph 定向测试和 `dependency:analyze` 通过 | 试点默认关闭，是否启用由部署环境决定 |
| 2. QuestionCard 与 Workflow Checkpoint 拆分 | 已完成 | `AgentQuestionCardModel/Store`、`AgentWorkflowCheckpointModel/Store`；V9 表和历史迁移；Thread `OPEN_INTERACTION_TYPE/ID`；`AgentQuestionAnswerAdmission`、`AgentWorkflowDecisionAdmission`；新 API/DTO；Core 状态机、MyBatis CAS、Turn 持久化、`request_user_input` 定向测试共 12 项 | 旧模型和兼容 API 在阶段六统一清理 |
| 3. 迁移固定订单 Workflow | V1/Legacy 保持兼容，V2 已实现待门禁，默认关闭 | `LangGraphAgentWorkflowEngine`、`LangGraphWorkflowGraphFactory.createExpediteV2Workflow` 和独立 `AgentWorkflowCheckpoint`；V11/V12 为 Run/图快照持久化 `LEGACY_V1`/`EXPEDITE_GRAPH_V1`/`EXPEDITE_GRAPH_V2`，明确订单号催发货新 Run 才能在显式模式进入 V1/V2；V2 Core 策略节点负责事实判断和命令草稿，批准事务只落库唯一命令，Worker 负责结果核验和续跑 | V2 并发、快照故障、指标、第三方鉴权和删除动作仍是发布门禁 |
| 4. 加固 V7 Continuation | 已完成 | `AgentContinuationGateway`、`TransactionalAgentContinuationGateway`；`AgentContinuationInput.idempotencyKey()` 覆盖根/父 Turn、Run、Command、状态、结果 Sequence 和 cycle；事务内首事实持久化、提交后入队、重复/并发 admission、STOP_LIMIT 和配置边界测试通过；`ExternalActionOutcomeManager` 已移除本地续跑创建并改用统一 Gateway；`e289bcb` 使队列暂满后的续跑重试重新读取持久化 Turn 状态，`ba0e248` 使提交后入队入口也先重读 Turn，避免取消竞态执行过期快照 | 代码、隔离现场和合成黄金路径已验收；生产第三方鉴权仍是部署门禁 |
| 5. 前端交互与状态投影 | 已完成 | `agent-fronted` 已统一目录/package；`QUESTION_CARD`、`QUESTION_ANSWER`、`WORKFLOW_CHECKPOINT`、`WORKFLOW_DECISION` 投影与三条新 API；QuestionCard/Checkpoint 独立卡片、历史 `WORKFLOW_QUESTION` 只读展示、七节点 Graph 状态、Continuation 提示、外部成功后的非阻断告警和 Sequence 追加快路径；订单卡片已改为 `DELETE_ORDER` 直接删除记录，Thread 列表不再展示回收站；`e94eb4b` 修正 Agent QuestionCard 合法的 `runId: null`，并覆盖真实 payload；typecheck、Vitest 和 production build 通过 | 当前工作区新增前端设计/文案改动需单独验收 |
| 6. 遗留代码和测试环境清理 | 已完成 | `e7c18c8` 删除旧 Question/Answer 模型、admission、事务 Workflow 引擎、旧 API DTO、Mapper/Store 和旧授权配置；`96b2e27` 删除无生产引用的重复 Workflow Answer 类型，历史 `WORKFLOW_ANSWER` 仍仅按消息标记读取；`01ad541` 修复规范门禁发现的 persistence 包、Clock 注入和测试命名问题。`FakeClientHttpRequestFactoryTest` 覆盖 HTTP 单测，真实 loopback 契约移至 `HttpOrderGatewayIT`/`HttpExternalActionExecutorIT`，Surefire 与 Failsafe 分离；`rg` 未发现旧生产入口或旧前端目录引用；`833765c` 清理依赖分析警告 | 生产开关、第三方鉴权和删除动作仍是部署门禁 |
| 7. 完整验收与交接 | 本地门禁通过，外部环境待复核 | 2026-08-27 本轮 `convention_check`、脚本 10 项、runtime eval 5 项、Maven Core 48/Infrastructure 72/App 17、前端 typecheck/Vitest/build 和无警告 `dependency:analyze` 均通过；`bd0fe9a` 收口 Checkpoint 恢复状态与 Spring Bean 装配，`a7e99b3` 收口事实变化时拒绝终态，`ba0e248` 收口提交后入队取消竞态；本轮修复两个 HTTP 网关多构造器注入标记后，加载用户级 `.env` 启动 Tomcat `8090`、MySQL、Flyway V9、订单夹具 `18080` 和前端 `5173`，健康检查 `200 UP`；`mvn verify` 真实 HTTP `*IT` 9 项通过；本轮直接删除订单记录的本地/HTTP/夹具协议、幂等重放和前端投影测试通过；2026-08-29 在只读源库 `COMMERCE_GUARDIAN_AGENT` 的一次性克隆 `COMMERCE_GUARDIAN_AGENT_MIGRATION_20260829` 中回退 V7 形态并重放 V8、V9，历史业务表计数与校验和未变化，V8 快照未回填 | 真实模型和浏览器黄金路径仍需复核 |

阶段二的兼容边界：旧 `AGENT_WORKFLOW_QUESTION`、旧 Turn 列和旧 `WORKFLOW_ANSWER` Item 仅由 V9 迁移脚本或前端历史投影读取；新的生产入口只写独立 QuestionCard/Checkpoint 表和 `QUESTION_ANSWER`/`WORKFLOW_DECISION` Turn。运行时代码不再提供旧 API、旧模型或旧 Workflow admission。详细执行日志不写入 handoff。

## 当前产品语义裁决（2026-08-28）

- Thread 不再提供回收站、归档/恢复入口；已有 `ARCHIVED` 状态和历史数据仅保留兼容读取，当前工作台只展示可继续使用的 Thread。
- Thread 更新接口只允许修改标题；历史 `ARCHIVED` Thread 不会因标题更新被恢复，当前产品没有归档写入口。
- 订单记录只支持退款、催发货和 `DELETE_ORDER` 直接删除；删除通过 `DELETE /orders/{id}` 同步清理可删除物流轨迹且不可恢复。
- `HIDE_ORDER`/`RESTORE_ORDER`、订单 `/visibility` 写接口和 Agent Tool 的 visibility 参数均已移除；旧枚举、`HIDDEN_AT` 列及历史 Item 仅为迁移/读取兼容，运行时代码不再写入隐藏状态。

## 订单售后 Workflow 计划追踪

| 计划阶段 | 当前结论 | 直接证据 | 未闭合事项 |
| --- | --- | --- | --- |
| 1. 数据库与状态基线 | 已验证（P2 运维差异已接受） | `5532463`；当前配置库与专用校准库实际启动到 Flyway 版本 5，`OPEN_QUESTION_ID`、Turn Workflow 字段、ExternalAction 版本/重试字段、结果表和幂等索引均存在。早于 V5 的已确认 V4 前备份导入专用克隆库 `COMMERCE_GUARDIAN_AGENT_V5_MIGRATION_20260823`，应用实际从版本 3 执行 V4、V5，保留 9 条订单、6 条物流事件并启动到版本 5；当前库和校准库另有 V5 后恢复快照 | 未单独生成 V5 命名的迁移前备份；已有前置备份覆盖 V5 前状态且克隆迁移/恢复快照已验证，作为 P2 运维差异记录，不影响本地数据完整性 |
| 2. QuestionCard 与实时交互收口 | 已验证完成 | `b1fc6bc`；动态字段、最多三选项、其他输入、Enter/Shift+Enter/IME、Escape、显式授权空默认值、受限 Markdown 表格、业务进度聚合和 SSE 断线恢复均有测试；真实浏览器刷新后 QuestionCard 恢复，未出现孤立 Waiting 或原始 delta | 无 |
| 3. 订单发现、物流诊断与 V4 Pro 契约 | 已验证完成 | `13500ba`、`56b631e`、`fcec19d`、`80c5ca9`；真实 DeepSeek V4 Pro 查询“列出今天最新订单”和“查物流三天没更新的订单”均完成并产生结构化 `ORDER_LIST`，浏览器展示订单卡片、物流时间线、业务进度和受限 Markdown 表格；独立 HTTP 订单服务的同类查询和物流时间线也已现场返回；最新 jar 的可选物流停滞参数空值/有值 Tool 回归均完成 | 无；第三方生产订单平台鉴权按部署环境另行验收 |
| 4. 统一 `ORDER_SERVICE` Workflow | 已验证（独立 HTTP 边界完成） | `49311ca`、`6d40351`、`7029122`、`22eb4de`、`80c5ca9`；历史浏览器证据覆盖退款拒绝、隐藏/恢复和催发货，隐藏/恢复结果现仅作为旧数据兼容参考；当前代码新增 `DELETE_ORDER` 的候选核验、`AUTHORIZE` Checkpoint、HTTP DELETE 适配器和本地/夹具幂等测试；独立 HTTP 订单服务真实响应“今天订单”查询，Agent 真实 QuestionCard 授权退款将远程订单更新为 `REFUNDED`；2026-08-29 V7→V8→V9 临时克隆重放保持历史业务事实与旧投影不变 | 真实模型/订单夹具/浏览器黄金路径和第三方生产鉴权仍属阶段七验收；V5 前置备份差异按 P2 接受并记录 |
| 5. 能力集与产品化收尾 | 已验证完成 | `fcec19d`、`b1fc6bc`；历史 Thread 行内重命名、ACTIVE/ARCHIVED 恢复证据保留在历史记录；当前工作台移除回收站/归档入口，订单卡片提供直接删除记录动作，移动端抽屉、订单卡片上下文动作和聚合进度继续保留；当前 typecheck、Vitest、production build 通过 | 真实浏览器需重新确认删除动作和无回收站界面 |

## 本轮阶段七代码评审验收（2026-08-28）

- `C:\Users\23260\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -m scripts.convention_check`：通过。
- `C:\Users\23260\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -m unittest discover -s scripts/tests -p "test_*.py"`：10 项通过；`scripts.runtime_eval` 的 5 个确定性门禁通过。
- `mvn clean '-DskipTests=false' test`：通过，Core 48、Infrastructure 72、App 17。
- `mvn dependency:analyze -DskipTests`：通过，三个 Maven 模块均无依赖问题；删除未使用的 `jspecify` 测试声明并移除 App Controller 冗余 `@Autowired`。
- `npm --prefix agent-fronted run typecheck`、`npm --prefix agent-fronted test -- --run`（35 项）和 `npm --prefix agent-fronted run build`：均通过。
- 代码评审回归：提交后入队和过期 Continuation 重试均重新读取最新 Turn；批准 Checkpoint 的事实变化会先失效并重核验，动作不再允许时安全失败且不创建命令，拒绝决策仍直接终止；Agent QuestionCard 允许 `runId: null`；无引用的旧 Workflow Answer 类型已删除；QuestionCard/Checkpoint Store 可被事务代理，Workflow Decision codec 已注册为 Bean。
- `mvn verify` 已按配置进入真实 `*IT`；`HttpOrderGatewayIT` 6 项与 `HttpExternalActionExecutorIT` 2 项全部通过，未再出现 loopback 错误。
- 本轮实际启动检查：第一次使用未加载 `.env` 的脚本时因 MySQL 空密码失败；随后修复两个 HTTP 网关的多构造器注入标记并从用户级 `.env` 注入数据库配置，应用完成 Tomcat `8090` 初始化、MySQL 连接和 V7→V8→V9 迁移，`GET /actuator/health` 返回 `200 UP`。订单夹具直接删除同一订单两次均返回 `ORDER_DELETED`，订单和物流查询均为 `404`；未修改项目代码以规避 Windows/JDK loopback。
- 本轮已重新运行数据库副本 V7→V8→V9；真实模型请求和浏览器黄金路径仍未重跑，下方历史现场证据继续保留，但不替代本轮重跑。

## 基线结论

- 本地代码门禁已通过：规范检查、脚本 10 项、runtime eval 5 项、Maven Core 48/Infrastructure 72/App 17，以及前端 typecheck、Vitest 35 项和 production build。
- `mvn dependency:analyze -DskipTests` 构建通过且无依赖问题。
- `mvn verify` 的真实 HTTP `*IT` 9 项已在当前环境通过；Fake Transport 单测与真实 HTTP 协议验收均已覆盖。
- 数据库副本 V7→V8→V9 已在只读源库的临时克隆中重跑并通过；真实模型/订单夹具/浏览器黄金路径本轮仍未重跑，历史现场记录需按发布前运行手册再次确认。
- 当前工作树仍包含用户既有的 `.idea`、部署、Docker、Hook、脚本和配置改动；本阶段未覆盖或混入这些改动。

## 第 2 周 Agent 质量评测基线（2026-08-31）

- 在隔离克隆中从 `codex/commerce-guardian-agent` 创建 `codex/agent-quality-eval`，审阅并吸收原工作区未提交的 `scripts/runtime_eval`，未覆盖原工作区。
- 新增内部 `EvalScenario` 格式，固定字段为 `id`、`prompt`、`setup`、`expectedDecision`、`requiredItems`、`forbiddenItems`、`maxOpenInteractions` 和 `expectedMutationCount`；该格式不进入 Core 或 HTTP DTO。
- 固定 12 个场景覆盖精确订单、今日订单、停滞物流、物流详情、退款缺订单、退款缺原因、退款拒绝/批准、催发货失败/人工重试、删除拒绝/批准。
- 确定性 runner 默认执行 12 场景 × 3 次，验证安全边界、幂等和路由/终止决策；本地结果为安全 36/36、路由 36/36。GitHub Actions 只调用该 runner 和定向单测，不连接真实模型、数据库或订单服务。
- 本机 `live_runner` 只接受已脱敏结构化观察结果，报告仅保留决策、Item 类型、开放交互数、变更数和通过状态；输出写入忽略目录，拒绝 Prompt、Thinking、原始响应、密钥和请求头字段。
## 第 1 周发布基线（2026-08-29）

- `e4e8dd6`（分支 `codex/demo-baseline`）新增确定性 GitHub Actions，分别执行 Python 规范/脚本单测、Maven 单测/集成测试/依赖分析，以及前端 typecheck/Vitest/组件测试/production build；工作流不调用真实模型或数据库副本。
- 根目录 `AGENTS.md` 新增三条 Code Review Rules：外部写操作必须经过持久化 Workflow/Checkpoint/ExternalActionCommand；`WORKFLOW_RESULT`/`EXTERNAL_ACTION_STATUS` 优先于 Turn 完成态；分页与 SSE 游标必须严格前进并在异常时安全收口。
- GitHub Actions `deterministic-ci #1`/`#2` 的失败已定位为 committed 旧 `docker-compose.yml` 和交接文本中的禁用内容；这些历史运行未带入原工作区的 Docker、部署或其他未提交文件。
- Week 1 PR #2 已按顺序合入集成分支，生成合并提交 `4742e25`；base=`codex/commerce-guardian-agent`、head=`codex/demo-baseline`，未改变 `master`。
- `a851b40` 已从 Week 1 分支移除废弃的 Docker 编排文件，`7e0745b` 修正文档禁用文本；本地 `convention_check` 与脚本 9 项测试通过，GitHub Actions `deterministic-ci #8`（push）和 `#9`（pull_request）全部成功。
- Codex 自动审查未发现 P0/P1；另有一条 P2 建议指出 CI 的 `git diff --check` 需要比较显式 base/head 范围，暂不影响本周三项门禁通过。

## 追踪矩阵

| 目标区域 | 当前结论 | 直接证据 | 优先级 | 下一步 |
| --- | --- | --- | --- | --- |
| 外部动作命令的 Lease、版本、重试与幂等 | 已验证（本地与独立 HTTP 服务完成） | `ExternalActionCommandModel`、`MybatisExternalActionCommandStore` 已有版本/CAS、Lease、总尝试和重试周期；`ExternalActionOutcomeManager` 在命令 CAS 成功后于同一本地事务投影 WorkflowRun、Turn 和结构化 Item；专用 MySQL 已验证单 Worker 成功、冲突终态投影回滚后 Lease 接管并复用同一结果、双 Worker 竞争只产生一次执行，以及失败触发器下重试耗尽后人工重试复用原命令/幂等键。`7029122` 强制订单写操作端口接收幂等键，HTTP 适配器以 `Idempotency-Key` 请求头发送；`22eb4de` 让空/非法键在出网前失败；`9fc19af` 验证本地回执故障后的协议级重放；当前本地/HTTP/夹具覆盖退款、催发货和 `DELETE_ORDER` 三类新写操作，旧隐藏/恢复值只验证为受控拒绝。另在专用库发现一条手工历史 Thread 的 `NEXT_SEQUENCE` 小于现有 Item 数量；仅修正校准数据为 `MAX(SEQUENCE_NO)+1` 后重启 Worker，命令成功且结果表仍为单行，未修改生产代码或原业务库 | P0 | 无；第三方生产订单平台鉴权按部署环境另行验收 |
| Thread → Turn → Item 事实一致性与恢复 | 已验证完成 | MyBatis 创建 Turn 与首个 Item 已在 Thread 锁事务内；`AgentTurnModel.version` 与 `AGENT_TURN.VERSION_NO` 形成单调 CAS，运行时在竞争失败时停止后续 Item/SSE，终态不可重写；`dd7a5c3` 将 Workflow Item ID 收敛为 UUID，避免真实数据库 64 字符边界溢出；专用 MySQL 已实证 ACTIVE Turn 重启收敛为 `FAILED/RUNTIME_RESTARTED` 并生成 `TURN_STATE`，两个 HTTP Turn 并发写入时生成 12 个唯一连续 Item Sequence；Item 插入故障返回 500 后 Thread/Turn/Item 全部回滚且 `NEXT_SEQUENCE=0` | P0 | 无；最终矩阵已通过 |
| QuestionCard / Checkpoint / WorkflowRun 状态机 | 已验证完成 | Question admission 已有 `reserve → enqueue → close/release` 的版本 CAS、事务回滚、回答 Turn 幂等和重启对账；`dd7a5c3` 使失败释放与当前版本 Question Item 在同一事务提交，真实浏览器已验证拒绝收敛为 `ANSWERED/CONSUMED/REJECTED` 并在重载后恢复；专用 MySQL 两路 HTTP 并发回答实际得到单个 202/单个 409，最终 Question 为 `ANSWERED（版本3）/CONSUMED`、WorkflowRun 为 `REJECTED(v1)`；回答 Turn 插入故障返回 500 后 Question 保持 `OPEN(v0)/AVAILABLE`、无回答 Turn/Item，Thread 指针和 `NEXT_SEQUENCE=0` 不变 | P0 | 无；最终矩阵已通过 |
| 外部动作成功/失败/人工重试 | 已验证完成 | `ExternalActionOutcomeManager` 统一写入 `EXTERNAL_ACTION_STATUS`、`TURN_STATE`，命令/Workflow/Turn/Item 在本地事务内收敛；专用 MySQL 已验证成功、失败重试耗尽、投影冲突回滚、Lease 接管、结果表单行幂等、双 Worker CAS，以及人工重试不产生第二条结果；校准库遗留序列计数修正后重启恢复为 `SUCCEEDED(v256)`，同一幂等结果仍只有 1 行，原失败 Turn 未被重写 | P0 | 无；最终矩阵已通过 |
| 外部动作人工重试状态收口 | 已验证完成 | `04a4c1c` 已验证 `MANUAL_RETRY_REQUIRED → WAITING_EXTERNAL_ACTION/COMPLETED`，真实 API 返回原 command/idempotencyKey；专用 MySQL 已验证耗尽后 API 重试、成功收敛、失败 Turn 不被重写、重复重试返回 409，结果表和幂等键各 1 行；`9dba42b` 修复结果类型映射 | P0 | 无；最终矩阵已通过 |
| 类型化 Item 与统一序列日志 | 主要生产链路已验证，进入跨层回放收口 | Core `AgentItemTypeEnum`、`AgentItemModel` 和 `AgentItemPayloadModel` 强制 `schemaVersion=1 + kind + data` envelope；`AgentItemPayloadValue` sealed value 模型已覆盖 18 类公开 payload；`AgentItemJournal` 统一序号、幂等追加和提交后事件；Runtime、Spring AI Tool、LangGraph、Worker Outcome、Continuation、QuestionCard/Workflow Decision、订单事实和错误路径已接入 Codec/Journal，Items API 增加兼容 `data` 字段，前端显式处理结构化错误并保留历史兼容；真实浏览器已展示受控业务事实，未展示 Tool JSON、事件名或 Thinking | P0 | 补 Items API 跨页回放、重复/乱序收口和 typed payload 幂等追加矩阵；未知 kind fallback 暂保留以兼容历史数据 |
| Context、摘要和敏感信息隔离 | 2A-1 已切换完整原始历史 | `AgentContextAssembler` 通过固定水位和 300 条分页读取原始 Item；旧摘要只保留兼容，不参与模型输入，超预算或历史不完整时受控停止。`SpringAiOrderToolSupport` 与上下文视图按最终 JSON 长度截断并保留关联字段；Core/Infrastructure 边界测试覆盖转义结果和内部 Item 隔离 | P1 | 2A-2 再验证摘要压缩、连续水位和重启恢复；真实 DeepSeek 运行时敏感信息检查待现场执行 |
| Spring AI / DeepSeek 请求契约 | 基础链路已验证，长期计划阶段 4 待定案 | `spring-ai-starter-model-deepseek` 保留 `stream().content()`、取消和超时分类；当前固定 `deepseek-v4-pro`，开启 thinking 与 `reasoning-effort=max`，`.env`/`.env.example` 已同步；已有 V4 Pro Tool Calling、浏览器订单 Workflow、SSE delta、取消、超时和敏感字段证据，未将 Thinking 或 delta 写入 Item、日志和前端 | P1 | 按长期计划阶段 4 完成 Pro/Flash 两套配置的 Live 对比，再决定是否切换并补齐证据 |
| Tool Calling 与 Workflow 边界 | 已验证完成 | Coordinator 将只读工具与 Workflow 工具分离，写操作进入确定性 Workflow；`131924a` 为每次 Tool Call/Result 写入稳定的 `invocationId`，按调用 ID 记录耗时和失败结果，并在 Tool wrapper 边界拒绝空订单号/退款原因；`0ed8688` 删除订单 Record 的隐式 `toString()` 输出，采用字段白名单和返回前 2000 字符边界；最终 Maven 132 项通过，真实 DeepSeek `lookup_order` Tool Call/Result 的 invocationId 匹配、结果长度 26，真实 SSE/取消/超时也已验证 | P1 | 无; 最终矩阵已通过 |
| SSE 断线恢复、去重、有序合并 | 已验证完成 | `AgentThreadEventStream` 已实现单连接 buffer → backlog → ordered flush → live、`eventId + sequence` 去重和晚绑定清理，并有并发单元测试；`cef1052` 让前端在 offline 时取消 reader、online 时从当前游标重连，并以无数据超时兜底；真实浏览器在 `afterSequence=13` 连接上切换 offline/online 后，实际恢复断线期间的 14–19 号 Item，网络记录出现两次 `events?afterSequence=13`，页面无重复且控制台无错误 | P0 | 无；最终矩阵已通过 |
| 前端线程切换与 QuestionCard | 已验证完成 | `useThreadWorkspace` 保留 generation、历史 AbortController、旧事件 Thread 过滤和切换期间禁用；QuestionCard 提交 `APPROVE/REJECT`，组件覆盖多 Question、刷新恢复、订单动作、无回收站列表、重命名和移动端抽屉；`91f2afb` 按结构化外部动作状态恢复 Turn 展示并接入人工重试；`9c0ce82` 修正动态“其他”输入断言的异步状态等待，当前 Vitest 通过 | P0 | 真实浏览器需补直接删除动作和无回收站确认 |
| API、SQL、配置、文档一致性 | 已验证（独立 HTTP 边界完成） | API/Item envelope/身份边界保持不变；增量 migration 已到 V5，增加 Run 步骤/状态、Question 步骤、外部动作索引和旧库兼容字段；本地与独立 HTTP 订单服务均实现 `/orders/search`、详情、物流、`/orders/{id}/refund`、`/orders/{id}/expedite` 和 `DELETE /orders/{id}` 契约，`7029122` 明确写操作使用 `Idempotency-Key`；订单 `/visibility` 写接口已删除，夹具 README 说明删除会清理物流；`.env`/`.env.example` 的 `deepseek-v4-pro` 契约仍同步 | P0 | 无；第三方生产鉴权按部署环境另行验收 |
| Runtime eval / acceptance / live eval | 已验证（独立 HTTP 边界完成） | 当前 runtime eval 是明确标注的确定性本地替身；`871a155` 将前端 Mock 组件脚本改名为 `test:component`；真实 HTTP acceptance 已在专用 MySQL 上通过 Thread 列表、创建、Item 恢复、Turn 入队、幂等和执行轨迹回放六项检查；`9fc19af` 增加 HTTP 网关/执行器协议级幂等回放，`80c5ca9` 增加独立 SQLite HTTP 服务的真实 Agent 查询、退款、适配器动作和服务端重放；真实 DeepSeek 和真实浏览器均已取得订单 Workflow、Tool Calling、流式、取消、超时和恢复证据 | P1 | 无；第三方生产鉴权按部署环境另行验收 |
| 清理旧实现、兼容层和无效测试 | 已验证完成 | `91f2afb` 已删除旧 SSE 结构化事件兼容集合；`rg` 未发现可达的旧供应商配置或实现，规则检查器中的旧 token 仅作为禁用文本回归规则；`871a155` 已清理误导性的 `test:e2e` 命名；被忽略的 `.env` 已删除旧 Router/ReAct、旧队列和旧 Worker 配置，只保留当前变量。runtime eval 的 Fake 类型、前端历史裸 payload fallback 和规则检查器回归文本均有明确边界，不是可证明应删除的生产旧实现；最终矩阵通过 | P2 | 无 |

## 当前里程碑边界

阶段一至六已由 `d74bc79`、`fa834b5`、`dbf4aa5`、`ebada3f`、`db73491`、`e7c18c8` 和 `01ad541` 完成并分别可回滚。本轮已完成 2A-1/2A-2、2B-1/2B-2、事项级恢复和前端结果投影的代码、本地/集成及隔离现场门禁；本轮已把 Codec/Journal 接入全部主要生产 Item 写入路径，并将 18 类 Core payload builder 接入受控值模型，Items API 增加兼容 `data` 字段。阶段 0/1 的规则、跨层 Items 回放、异常游标收口和幂等追加已在随机 MySQL 8.4 acceptance 与完整本地门禁通过；V2 节点、分支、快照重建、命令去重和指标断言也已通过本地回归。handoff 仍保持 `active`，因为真实 DeepSeek 对比、目标环境并发/回执、第三方鉴权、删除动作和剩余视口专项尚未完成；未知 kind fallback 仍作为历史兼容边界保留。不能把“默认关闭”误读为“代码未完成”，也不能把本地门禁误读为生产发布通过。历史现场证据仍保留在下方，但必须与当前结论区分。

## 历史外部验证边界（不替代本轮阶段七重跑）

本轮追加校准：当前配置库与专用校准库实际启动到 Flyway 版本 5；专用克隆库 `COMMERCE_GUARDIAN_AGENT_V5_MIGRATION_20260823` 从已确认的、早于 V5 的迁移前备份导入后由版本 3 增量执行 V4、V5，保留 9 条订单、6 条物流事件并成功启动；当前库和校准库均有 V5 后恢复快照。真实浏览器已完成订单 Workflow、QuestionCard 刷新、Thread 回收站、移动端抽屉和业务进度验收；本轮独立 HTTP 订单服务也已完成 Agent 查询、退款、适配器动作和服务端幂等重放验收。V5 未单独生成命名备份的差异已按 P2 运维记录接受。

本轮追加代码校准：提交 `7029122` 强制所有订单写操作端口接收命令幂等键，HTTP 适配器把该键发送为 `Idempotency-Key` 请求头；当时的四类动作传播测试和 HTTP 请求头契约测试通过。当前订单写操作收敛为退款、催发货和删除，隐藏/恢复历史测试不再代表现行产品契约。该修复降低远程成功后本地回执提交失败时的重复写入风险，但真实外部订单服务仍需凭据验证其服务端去重实现。

本轮独立 HTTP 验收：提交 `80c5ca9` 新增不共享 Agent MySQL 的订单服务夹具。真实 Agent 通过 `127.0.0.1:18080` 完成订单搜索和退款 QuestionCard，远程 `ORDER-EXT-STALLED-001` 最终为 `REFUNDED`；同一 Workflow 幂等键重放不新增服务端记录或业务变更。历史 Java `HttpOrderGateway` 对 `EXPEDITE`、`HIDDEN`、`ACTIVE` 各执行一次并重复一次的证据仅用于旧契约追踪；当前夹具已移除 visibility 写接口并新增 DELETE 幂等/物流清理测试。该服务只由独立 Python 进程运行，不属于 CD 或部署能力。

已确认专用校准边界为本机 `127.0.0.1:3306/COMMERCE_GUARDIAN_AGENT_CALIBRATION_20260821`，当前只在该库导入基线；原 `COMMERCE_GUARDIAN_AGENT` 未重建。数据库日志和命令输出均未打印密码；本轮 Thread/QuestionCard 故障触发器只存在于专用库，验证后已移除。Context 长历史探针未删除业务事实，使用独立校准 Thread 并记录 246 个 Item、8 个快照和重启后上下文事件；另对一条手工遗留校准 Thread 的错误 `NEXT_SEQUENCE` 做了仅限该专用库的计数修正，重启后确认 Worker 复用单一幂等结果且未修改生产代码。真实 DeepSeek 已在同一专用库完成 Tool Calling、71 个 SSE delta、流中取消、短时限超时和敏感信息检查；早期“订单售后前端真实浏览器验收尚未开始”属于历史记录，当前浏览器证据见本文顶部及阶段 5 行。复核被 Git 忽略的 App `.env` 后删除了旧 `AI_AGENT_MODEL_*`、Router/ReAct、旧队列和旧 Worker 变量，并使其与 `.env.example` 的变量集合和非敏感默认值一致；Spring Boot 不自动加载该文件，必须显式注入进程，且真实 key 只保留在 `.env`。不能以本地替身替代真实模型证据，也不能把阶段性 P0/P1 运行时证据误报为本计划最终完成。
本阶段新增数据库证据：当前配置库和专用校准库均在确认备份/克隆边界后由 Flyway 从版本 2 增量执行版本 4，`STEPS_JSON`、`STATE_JSON` 为非空，Question 外键恢复，唯一键为 `(RUN_ID, STEP_NO)`，V4 `IDX_EXTERNAL_ACTION_THREAD_STATUS` 已存在；两个库均保留 9 条订单、6 条物流事件，应用实际启动并响应 Thread/Question API 后暂时运行在 8091/8092 供浏览器验收。V3 退款以及 V4 催发货/隐藏/恢复验证均使用专用校准库和真实 DeepSeek Tool Calling，未将密钥或 Thinking 写入数据库；隐藏/恢复部分为历史证据，当前新增的删除协议以代码和夹具测试为准。本轮新增的独立 HTTP 订单服务使用独立 SQLite 并完成查询、物流、Agent 退款、Java 适配器写操作和服务端幂等验证，不把该本机夹具表述为第三方生产平台。

## P7 隔离交付验收追踪（2026-09-28）

P7 已完成一轮可复现的隔离启动和 HTTP/数据库门禁，但保持 active，不替代 P6 Live 或发布回滚门槛。最终 Jar 在一次性 MySQL schema `CGA_P7_DD3737D06C15407B`（Flyway v14）启动，独立订单夹具在 18080 启动；`python -m scripts.acceptance` 覆盖 Thread/Item 恢复、交互唯一性、Turn 幂等、物流、退款幂等、催发货临时失败重试和删除幂等并通过。干净快照 `mvn clean test` 为 Core 98、Infrastructure 109、App 22，profiles 为 HTTP 9、MySQL 13；MySQL acceptance 内含 601 条 Items 固定水位分页、异常游标收口、跨会话恢复和并发 CAS，App/前端自动化测试覆盖 SSE 回放去重和重连；前端 typecheck、Vitest 77、build 通过，浏览器默认桌面连接和服务不可用时的重连提示已复核。

未闭合：两套真实模型各 54 条 Live 评测、兼容版本→最终版本→兼容版本回滚、真实浏览器 SSE 断线、窄屏/移动端专项和目标环境旧 Run/continuation 只读盘点。上述证据不表示真实生产订单平台或生产部署已验收。
