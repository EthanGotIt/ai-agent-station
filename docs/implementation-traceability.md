# Commerce Guardian Agent 实现追踪矩阵

> 状态：`active`
> 更新日期：2026-08-21
> 目标来源：任务 `01a01f3f-2a0e-7e52-b70e-4137e4ff3496` 的最新计划、当前工作树、Git 历史、架构文档、SQL、测试和实际运行结果。

本矩阵只把代码、测试和运行结果作为证据。原计划或 `docs/task-handoff.md` 中的“已完成”描述不能单独作为完成证据。

## 基线结论

- `scripts.convention_check`：通过；`e83b9c9` 校准了当前 DeepSeek 供应商契约和 Spring Boot 4/Jackson 3 直接依赖规则，仍保留旧项目/旧版本标记等禁用文本检查。
- `scripts/tests`：4 个测试通过，新增规则回归测试覆盖当前供应商和 JSON 依赖边界。
- Maven clean test：112 个测试通过（core 48、infrastructure 49、app 15）。
- 前端 typecheck：通过；Vitest：17 个测试通过；生产构建：通过。
- 以上本地测试已补充专用 MySQL 启动、重启恢复、并发回答 HTTP、ExternalAction Worker 状态矩阵和 Context 长历史快照续接实证；真实浏览器已补充 Thread/QuestionCard/重载恢复和 SSE 游标续传证据，真实 DeepSeek 仍尚未验证，不能据此宣称目标完成。
- 当前工作树包含此前任务产生的功能代码和用户既有的 `.idea`、部署、Docker、Hook 等改动；本轮不得覆盖或混入后者。

## 追踪矩阵

| 目标区域 | 当前结论 | 直接证据 | 优先级 | 下一步 |
| --- | --- | --- | --- | --- |
| 外部动作命令的 Lease、版本、重试与幂等 | 已验证完成 | `ExternalActionCommandModel`、`MybatisExternalActionCommandStore` 已有版本/CAS、Lease、总尝试和重试周期；`ExternalActionOutcomeManager` 在命令 CAS 成功后于同一本地事务投影 WorkflowRun、Turn 和结构化 Item；专用 MySQL 已验证单 Worker 成功、冲突终态投影回滚后 Lease 接管并复用同一结果、双 Worker 竞争只产生一次执行，以及失败触发器下重试耗尽后人工重试复用原命令/幂等键 | P0 | 保留专用库证据，纳入最终完整矩阵 |
| Thread → Turn → Item 事实一致性与恢复 | 已验证完成 | MyBatis 创建 Turn 与首个 Item 已在 Thread 锁事务内；`AgentTurnModel.version` 与 `AGENT_TURN.VERSION_NO` 形成单调 CAS，运行时在竞争失败时停止后续 Item/SSE，终态不可重写；`dd7a5c3` 将 Workflow Item ID 收敛为 UUID，避免真实数据库 64 字符边界溢出；专用 MySQL 已实证 ACTIVE Turn 重启收敛为 `FAILED/RUNTIME_RESTARTED` 并生成 `TURN_STATE`，两个 HTTP Turn 并发写入时生成 12 个唯一连续 Item Sequence；Item 插入故障返回 500 后 Thread/Turn/Item 全部回滚且 `NEXT_SEQUENCE=0` | P0 | 纳入最终完整矩阵 |
| QuestionCard / Checkpoint / WorkflowRun 状态机 | 已验证完成 | Question admission 已有 `reserve → enqueue → close/release` 的版本 CAS、事务回滚、回答 Turn 幂等和重启对账；`dd7a5c3` 使失败释放与当前版本 Question Item 在同一事务提交，真实浏览器已验证拒绝收敛为 `ANSWERED/CONSUMED/REJECTED` 并在重载后恢复；专用 MySQL 两路 HTTP 并发回答实际得到单个 202/单个 409，最终 Question 为 `ANSWERED（版本3）/CONSUMED`、WorkflowRun 为 `REJECTED(v1)`；回答 Turn 插入故障返回 500 后 Question 保持 `OPEN(v0)/AVAILABLE`、无回答 Turn/Item，Thread 指针和 `NEXT_SEQUENCE=0` 不变 | P0 | 纳入最终完整矩阵 |
| 外部动作成功/失败/人工重试 | 已验证完成 | `ExternalActionOutcomeManager` 统一写入 `EXTERNAL_ACTION_STATUS`、`TURN_STATE`，命令/Workflow/Turn/Item 在本地事务内收敛；专用 MySQL 已验证成功、失败重试耗尽、投影冲突回滚、Lease 接管、结果表单行幂等、双 Worker CAS，以及人工重试不产生第二条结果 | P0 | 保留专用库证据，纳入最终完整矩阵 |
| 外部动作人工重试状态收口 | 已验证完成 | `04a4c1c` 已验证 `MANUAL_RETRY_REQUIRED → WAITING_EXTERNAL_ACTION/COMPLETED`，真实 API 返回原 command/idempotencyKey；专用 MySQL 已验证耗尽后 API 重试、成功收敛、失败 Turn 不被重写、重复重试返回 409，结果表和幂等键各 1 行；`9dba42b` 修复结果类型映射 | P0 | 保留专用库证据，纳入最终完整矩阵 |
| 类型化 Item 与统一序列日志 | 部分实现 | `AgentItemModel` 只有 envelope + 字符串 `data`；部分适配器使用 Jackson，部分代码手写 JSON/字符串；SSE/前端仍保留宽松 fallback | P1 | 在稳定动作事务后统一 codec、Item journal 和边界解码 |
| Context、摘要和敏感信息隔离 | 部分实现 | `401e856` 让 Context 通过最新窗口查询、当前请求预算和原始终态 Item 识别摘要边界；Core 7 项测试覆盖严格预算、最新窗口、摘要失败降级、快照安全前缀和内部 Item 隔离。`0ed8688` 让订单/物流 Tool 结果只投影模型安全业务字段并在返回前截断，Infrastructure Tool 边界 4 项通过。专用 MySQL 长历史探针实际得到 246 个 Item、8 个快照（版本 1–8，最新覆盖序列 210）；重启后的 `CONTEXT_ASSEMBLED` 事实读取 `snapshotThroughSequence=156`，后续压缩事件为 `compressed=true/degraded=false`。仍缺少真实供应商上下文窗口和敏感数据运行时观测证据 | P1 | 完成类型化 Item/边界解码审查，并在凭据可用时验证真实模型输入边界 |
| Spring AI / DeepSeek 请求契约 | 部分实现 | 提交 `c5ca160` 已切换 `spring-ai-starter-model-deepseek`，配置固定 `deepseek-chat`、`max-tokens`、单次重试、连接/读取超时；本地 2.0.0 自动配置类确认使用 `spring.ai.deepseek` 与 `spring.ai.deepseek.chat` 前缀；Coordinator 使用 `stream().content()`，明确区分模型错误、取消和超时，并有 5 项流式协调测试；真实 DeepSeek 凭据/请求/Tool Calling 尚未验证 | P1 | 凭据可用时执行真实 DeepSeek Tool Calling、流式、取消、超时和敏感信息检查 |
| Tool Calling 与 Workflow 边界 | 部分实现 | Coordinator 将只读工具与 Workflow 工具分离，写操作进入确定性 Workflow；`131924a` 为每次 Tool Call/Result 写入稳定的 `invocationId`，按调用 ID 记录耗时和失败结果，并在 Tool wrapper 边界拒绝空订单号/退款原因；`0ed8688` 删除订单 Record 的隐式 `toString()` 输出，采用字段白名单和返回前 2000 字符边界；4 项 Tool 边界测试和全量 Maven 112 项通过，但真实模型 Tool Calling、供应商错误分类和 live eval 尚未验证 | P1 | 凭据可用时验证真实 Tool Calling、参数契约、错误分类和 live eval |
| SSE 断线恢复、去重、有序合并 | 已验证完成 | `AgentThreadEventStream` 已实现单连接 buffer → backlog → ordered flush → live、`eventId + sequence` 去重和晚绑定清理，并有并发单元测试；`cef1052` 让前端在 offline 时取消 reader、online 时从当前游标重连，并以无数据超时兜底；真实浏览器在 `afterSequence=13` 连接上切换 offline/online 后，实际恢复断线期间的 14–19 号 Item，网络记录出现两次 `events?afterSequence=13`，页面无重复且控制台无错误 | P0 | 保留专用 MySQL 与真实浏览器证据，纳入最终完整矩阵 |
| 前端线程切换与 QuestionCard | 已验证完成 | `useThreadWorkspace` 已有 generation、历史 AbortController、旧事件 Thread 过滤和切换期间禁用；QuestionCard 已提交 `APPROVE/REJECT`，组件测试覆盖迟到历史和答案体；`91f2afb` 按结构化外部动作状态恢复 Turn 展示并接入人工重试；真实浏览器已验证创建、重命名、切换、QuestionCard 拒绝、失败 Turn、人工重试和重载恢复，重试后最终成功 Action Item 不会覆盖失败 Turn 事实终态 | P0 | 保留真实浏览器与 17 项前端测试证据，纳入最终完整矩阵 |
| API、SQL、配置、文档一致性 | 部分实现 | API 路径、Item envelope、DeepSeek 配置和 `.env.example` 已在后端提交中对齐；`d6d22ab` 统一 Core/DTO/Header 的身份、标题、上下文和普通消息边界，`5c6f1f5` 又保证规范化消息同时进入 Turn 和首个 `USER_MESSAGE` Item，并在 SQL `INPUT_TEXT` 注明结构化 Workflow 回答仍使用 10000 字符存储列；真实浏览器已核对 items/events 请求、`afterSequence` 游标和 SSE `item.*` 事件；`e83b9c9` 使规则检查与 Jackson 3 依赖边界一致，`d09ca23` 按源码调用路径补齐直接依赖且 Maven dependency analyze 三模块无 warning；专用 MySQL 长历史和重启探针已验证 API、SQL、Item/Snapshot 事实能够持续恢复；旧数据库因未执行基线会缺少新列，运行手册已明确只能在可丢弃库执行基线 | P1 | 审查剩余 DTO、分页参数和异常响应路径，再运行全契约矩阵 |
| Runtime eval / acceptance / live eval | 部分实现 | 当前 runtime eval 是明确标注的确定性本地替身；`871a155` 将前端 Mock 组件脚本改名为 `test:component`；真实 HTTP acceptance 已在专用 MySQL 上通过 Thread 列表、创建、Item 恢复、Turn 入队、幂等和执行轨迹回放六项检查；真实浏览器已取得本地模型失败和 QuestionCard 重载恢复证据，但真实 DeepSeek live eval 仍未取得运行证据 | P1 | 在凭据可用时执行 DeepSeek Tool Calling、流式、取消、超时和 live eval，否则保留未验证结论 |
| 清理旧实现、兼容层和无效测试 | 部分实现 | `91f2afb` 已删除 `sse.ts` 中不符合当前 `ready/heartbeat/item.* /turn.*` 契约的旧结构化事件兼容集合；`rg` 未发现可达的 OpenAI/Qwen 配置或实现，`qwen3.7-max` 仅存在于 convention checker 的禁用文本回归规则；`871a155` 已清理误导性的 `test:e2e` 命名，runtime eval 的 Fake 类型是文档明确的确定性门禁而非生产实现 | P2 | 对剩余测试替身和禁用规则保留“有意隔离”的依据，禁止盲删 |

## 当前里程碑边界

本轮已完成外部动作本地投影事务收口（`3e5e4a0`）、SSE 单连接回放/实时合并收口（`c927ca0`）、Turn 版本 CAS 与终态保护（`0c836c1`）、QuestionCard/WorkflowRun 状态机收口（`04a4c1c`）、Runtime/DeepSeek/Boot 兼容与超时分类收口（`c5ca160`）、Workflow 回答失败重试投影与 Item ID 边界收口（`dd7a5c3`）、SSE 异步连接结束边界收口（`821733c`）、外部动作结果实体映射收口（`9dba42b`）、前端外部动作状态与人工重试契约收口（`91f2afb`）、前端 SSE 断线续传收口（`cef1052`）、规则检查器与运行时依赖契约校准（`e83b9c9`）、直接依赖收口（`d09ca23`）、Tool 调用关联/参数边界收口（`131924a`）、Context 最新历史/严格预算收口（`401e856`）、Tool 结果字段隔离收口（`0ed8688`）、输入身份/API 边界收口（`d6d22ab`）、前端测试命名收口（`871a155`）、真实长历史 Context/快照恢复与 HTTP acceptance 证据校准以及普通 Turn 输入事实一致性收口（`5c6f1f5`）。当前全量 Maven 基线仍为 112 项；`AgentTurnRuntimeServiceTest` 聚焦测试为 4 项通过；已完成专用 MySQL 启动、重启恢复、Context 快照续接、Thread→Turn→Item 并发/回滚、QuestionCard 并发/CAS/回滚、ExternalAction Lease/CAS/回滚/幂等/双 Worker/重试耗尽/人工重试实证，以及真实浏览器 QuestionCard/人工重试/重载恢复/断线续传实证；仍须完成 Item/边界解码和 API 分页异常路径审查、真实 DeepSeek 和最终完整验证矩阵。

## 外部验证边界

已确认专用校准边界为本机 `127.0.0.1:3306/COMMERCE_GUARDIAN_AGENT_CALIBRATION_20260821`，当前只在该库导入基线；原 `COMMERCE_GUARDIAN_AGENT` 未重建。数据库日志和命令输出均未打印密码；本轮 Thread/QuestionCard 故障触发器只存在于专用库，验证后已移除。Context 长历史探针未删除业务事实，使用独立校准 Thread 并记录 246 个 Item、8 个快照和重启后上下文事件；真实浏览器已执行 Thread/QuestionCard/重载恢复和 offline/online 游标续传流程；真实 DeepSeek 尚未验证。当前环境没有可用的 DeepSeek 凭据，探针仅使用假值/本机不可达端点，因此真实模型验证必须继续标记为未验证，不能用替身测试替代。
