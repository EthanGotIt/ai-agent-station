# Commerce Guardian Agent 界面设计记录

本文件记录本轮实际落地页面的设计结果，不作为未实现的设计意图或视觉承诺。

## 页面骨架

- 顶部为单行产品栏：`Commerce Guardian Agent｜订单调度台` 在左、当前账户与演示账户切换在右；桌面端不再额外占用一段营销式标题区。Thread 只提供重命名，不提供回收站或归档恢复入口。
- 工作区在顶栏下直接进入三栏信息层级：宽屏按设计稿比例使用约 23.5% 的 Thread 列、约 49% 的中央 Turn 业务流和约 27.5% 的 Item 检查器（分别钳制为 280–376px 与 340–440px）；1024px 及以下检查器改为抽屉。
- 检查器打开后只显示当前 Turn 的完整持久化 Item 序列；主流仍保持业务结果优先。
- 1180px 以下检查器变为右侧抽屉，760px 以下覆盖为全屏面板；移动端 Thread 列表也变为可关闭抽屉。

## 视觉系统

页面使用更轻的冷雾灰画布（`#F4F7F6`）和清晰的白色工作面（`#FFFFFF`）；侧栏与右侧检查器以细分隔线而非厚重卡片阴影分区。路线青绿（`#176C60`）表达正常业务流，信号琥珀（`#C8792A`）表达等待确认或外部系统尚未核验，故障红（`#B6403C`）表达失败与错误。

正文采用 `Segoe UI Variable`、中文系统字体；时间、序号和 Item 类型采用 `Cascadia Mono`。按钮、面板、状态胶囊和输入控件共享 8–12px 圆角与 180ms 状态过渡。系统深色模式通过同一组语义 token 覆盖，`prefers-reduced-motion` 会收敛过渡和动画。

## 交互结果

- 空对话只提示“直接输入请求”，不再渲染固定快捷问。
- Turn 只展示一行由真实 Item 聚合出的阶段摘要、状态和耗时；订单列表、订单详情与物流时间线在同一 Turn 内以内联结构化卡片呈现。
- 订单卡片操作走确定性 `order-actions` Turn：查询直接读取订单/物流事实，退款、催发货和直接删除订单记录进入现有 Workflow；不会经过模型，也不会生成可见的模拟问答。删除会同步清理可删除的物流轨迹且不可恢复。缺少业务信息时由 QuestionCard 提问，外部写操作在 `AUTHORIZE` 节点使用独立 Workflow Checkpoint 确认。
- 订单动作 Turn、QuestionCard 回答 Turn 和 Workflow 决策 Turn 按 `sourceTurnId`、`runId`、`orderId` 折回来源 Turn；刷新后仍由持久化 `ORDER_ACTION_REQUEST`、`QUESTION_ANSWER`、`WORKFLOW_DECISION` 和结构化订单 Item 恢复同一张卡片。历史 `WORKFLOW_QUESTION`/`WORKFLOW_ANSWER` 仅在检查器只读展示。
- 执行类订单动作在来源 Turn 的对应订单卡片内显示单一状态回执：排队、处理中、等待确认、失败/人工重试和完成均绑定真实动作 Item；查询和刷新完成后直接更新同一张结构化卡片。页面不再显示重复的执行消息气泡或独立动作弹窗。
- QuestionCard 的普通步骤按钮为“继续”，取消按钮为“结束本次提问”；Workflow Checkpoint 只展示动作、对象、影响和“确认执行/拒绝执行”。QuestionCard 取消不会触发必填字段校验；Checkpoint 拒绝关闭 Workflow 且不创建外部动作。
- 活跃 QuestionCard 不再嵌入历史 Turn，而是作为页面中央、避开底部 Composer 的模态浮层；它带遮罩、关闭按钮、Esc 取消、焦点收束和独立滚动，底部输入框保留但在确认期间禁用，历史 Turn 只保留阶段摘要与卡内动作回执。
- `运行详情`显示浅色账本式真实 sequence、时间、Item 类型和受控 JSON；敏感键会被遮蔽，不展示 Thinking。Escape、关闭按钮和遮罩均可退出，抽屉打开时锁定页面滚动并将焦点移至关闭按钮。
- 终态 Turn 首次打开检查器时按需读取并缓存只读执行回放；回放失败时保留已由 Items/SSE 恢复的事实，并在检查器内给出降级提示。
- QuestionCard 使用后端提供的 `operation`、`step`、`stepNo`；外部动作回执区分已核验与“操作已受理、最新状态暂未核验”，后者提供可编辑的重新查询入口。

## 受控闭环投影

- 订单 Workflow 的实际节点固定为 `RESOLVE_ORDER → VERIFY_FACTS → SWITCH_REQUIREMENTS → AUTHORIZE → EXECUTE_ACTION → VERIFY_OUTCOME → HANDOFF_AGENT`。主区只显示当前业务摘要和结果；节点分支、耗时、错误码和 Agent 决策放在运行详情中按需查看。
- 外部动作成功或人工重试耗尽，以及授权时发现订单事实/执行资格变化后，服务端在本地事务中追加 `AGENT_CONTINUATION` Turn 和触发 Item，事务提交后才进入同 Thread FIFO。续跑最多 3 轮；开放 QuestionCard 存在时延后，不抢占用户回答。
- 续跑 Turn、Workflow 回答 Turn 和订单动作 Turn 都按 `rootTurnId/sourceTurnId/runId` 折叠回原业务 Turn。用户不会看到技术性的模拟问答；只在检查器中看到完整序列。
- QuestionCard schema 只描述待补充字段与 `resumeTarget=AGENT|WORKFLOW`；执行确认由 Workflow Checkpoint 的动作摘要、事实指纹和版本单独描述。两种交互均通过各自的回答/决策 Turn 收口，取消或拒绝不创建外部动作。

## 验收记录

- 2026-08-26 受控闭环增量已在一次性克隆库 `COMMERCE_GUARDIAN_AGENT_V7_MIGRATION_20260826` 完成 V6→V7 演练：应用启动触发 Flyway 7 并确认 `AGENT_TURN.CONTINUATION_JSON` 可空、历史 88 条 Turn 保持不变；原配置库保持 V6 且未增加该列。2026-08-27 为 DeepSeek RestClient 切换到项目已有的 Reactor Netty，并为 Tomcat 增加可配置的 NIO2 协议；本机用真实配置启动后端成功，`/actuator/health` 返回 200。当前 Codex Windows 沙箱在真正调用 DeepSeek 时仍会阻断 Netty selector 的 loopback 管道，页面会安全收敛为“Agent 执行失败”，不代表订单数据或数据库异常。
- `npm.cmd run typecheck`、`npm.cmd test -- --run`、`npm.cmd run build` 已通过；当前 Vitest 为 28 项，覆盖多 Turn/Item 聚合、旧 SSE 增量忽略、快捷问消失、完整 Item 检查器、回放失败降级、结构化订单卡片直达动作、卡内动作状态回执、居中 QuestionCard 模态浮层和取消后 QuestionCard 收敛。
- `mvn -q -DskipTests compile`、订单 Workflow/Worker/Spring AI 定向测试和 `mvn -q dependency:analyze -DskipTests` 已通过；Python convention、脚本单测和 `scripts.runtime_eval` 使用仓库配置的显式解释器通过。完整 Maven 测试中的 HTTP 适配器测试仍受当前 Windows 环境无法建立 JDK loopback connection 限制，未将环境错误当作产品通过证据。
- Impeccable detector 扫描 `agent-fronted/src` 返回空问题集。
- 已使用真实后端数据复核 1920×900、1440×900、1024×768、390×844：桌面三栏、1024 右侧抽屉与移动端全屏检查器均可用；居中 QuestionCard 避开底部 Composer，关闭检查器后移动端主业务流保持可操作。现场还验证了空退款原因 `CANCEL`、订单卡片 `QUERY_LOGISTICS` 直达请求，以及催发货 3 次夹具失败→人工重试→第 4 次成功的卡内回执（`injectedFailures=3`、`businessMutations=1`）。正文基准为 14px，页面/Turn 标题为 16px，卡片标题为 15px，技术元数据使用 11–12px 等宽字体；可复现启停与三条黄金路径见 `docs/review-runbook.md`。
