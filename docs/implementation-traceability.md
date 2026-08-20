# Commerce Guardian Agent 实现追踪矩阵

> 状态：`active`
> 更新日期：2026-08-21
> 目标来源：任务 `01a01f3f-2a0e-7e52-b70e-4137e4ff3496` 的最新计划、当前工作树、Git 历史、架构文档、SQL、测试和实际运行结果。

本矩阵只把代码、测试和运行结果作为证据。原计划或 `docs/task-handoff.md` 中的“已完成”描述不能单独作为完成证据。

## 基线结论

- `scripts.convention_check`：通过。
- `scripts/tests`：3 个测试通过。
- Maven clean test：67 个测试通过（core 35、infrastructure 27、app 5）。
- 前端 typecheck：通过；Vitest：12 个测试通过；生产构建：通过。
- 以上均为本地静态、单元或替身验证；MySQL 锁/CAS/重启、真实浏览器和真实 DeepSeek 尚未验证，不能据此宣称目标完成。
- 当前工作树包含此前任务产生的功能代码和用户既有的 `.idea`、部署、Docker、Hook 等改动；本轮不得覆盖或混入后者。

## 追踪矩阵

| 目标区域 | 当前结论 | 直接证据 | 优先级 | 下一步 |
| --- | --- | --- | --- | --- |
| 外部动作命令的 Lease、版本、重试与幂等 | 部分实现 | `ExternalActionCommandModel`、`MybatisExternalActionCommandStore` 已有版本/CAS、Lease、总尝试和重试周期；`ExternalActionWorker` 在远程调用后先单独更新命令，再分别更新 Workflow/Turn/Item | P0 | 将命令和本地事实投影收敛到一个本地事务，并补失败路径证据 |
| Thread → Turn → Item 事实一致性与恢复 | 部分实现 | MyBatis 创建 Turn 与首个 Item 已在 Thread 锁事务内；运行时恢复和序列测试存在；Turn 普通更新缺少版本 CAS，Worker 投影可出现半完成 | P0 | 先修复动作投影，再审计 Turn CAS、重启和序列恢复 |
| QuestionCard / Checkpoint / WorkflowRun 状态机 | 部分实现 | Question admission 已有保留、入队、关闭和重复回答控制；Workflow engine 有本地事务；SQL 缺少“同一来源 Turn 唯一 WorkflowRun”约束，取消/超时恢复仍需验证 | P0 | 完成动作事务后补来源 Turn、释放保留和恢复测试 |
| 外部动作成功/失败/人工重试 | 实现错误 | Worker 使用管道拼接的 `EXTERNAL_ACTION_STATUS` 数据，并可能在命令成功后投影失败；没有统一的重试后本地收敛事务 | P0 | 使用结构化 Item，并让 CAS 失败阻止全部后续投影 |
| 类型化 Item 与统一序列日志 | 部分实现 | `AgentItemModel` 只有 envelope + 字符串 `data`；部分适配器使用 Jackson，部分代码手写 JSON/字符串；SSE/前端仍保留宽松 fallback | P1 | 在稳定动作事务后统一 codec、Item journal 和边界解码 |
| Context、摘要和敏感信息隔离 | 部分实现 | 已有快照/窗口/截断测试和运行时组装器；完整历史倒序、摘要失败降级和敏感字段隔离缺少真实恢复证据 | P1 | 审计上下文窗口、摘要端口和真实历史读取 |
| Spring AI / DeepSeek 请求契约 | 偏离目标 | POM 使用 OpenAI starter；`application.yml` 使用 DashScope/Qwen；Coordinator 使用非流式 `call()`，无明确 `max_tokens`、thinking 禁用和错误分类 | P1 | 切换专用 DeepSeek 配置与真实请求/取消/流式验证 |
| Tool Calling 与 Workflow 边界 | 部分实现 | Coordinator 将只读工具与 Workflow 工具分离，写操作进入确定性 Workflow；调用关联仍以工具名为主，缺少稳定 invocationId 和真实模型证据 | P1 | 补调用关联、参数校验、错误分类和 live eval |
| SSE 断线恢复、去重、有序合并 | 实现错误 | Controller 先订阅再回放，但 backlog 与 live 回调并行发送且没有每连接串行缓冲；存在重复/乱序竞态 | P0 | 设计 buffer → backlog → ordered flush → live 的单连接管线并补测试 |
| 前端线程切换与 QuestionCard | 实现错误 | `useThreadWorkspace` 没有请求 generation/AbortController 保护；旧事件未严格按当前 Thread 过滤；QuestionCard 默认提交 `CONFIRM`，后端只接受 `APPROVE/REJECT` | P0 | 先修复参数契约和切换竞态，再做真实浏览器流程 |
| API、SQL、配置、文档一致性 | 部分实现 | API 路径和 Item envelope 基本对齐；DeepSeek 配置、SQL 来源 Turn 唯一约束、前端事件契约和 `.env.example` 仍不一致 | P1 | 随对应实现同步更新并运行规则检查 |
| Runtime eval / acceptance / live eval | 缺少验证 | 当前 runtime eval 是确定性本地替身；前端 `test:e2e` 实际运行 Vitest；真实 acceptance/live 流程尚未取得运行证据 | P1 | 改为真实 Java runtime、真实浏览器、专用 MySQL 和凭据可用时的 DeepSeek 验证 |
| 清理旧实现、兼容层和无效测试 | 缺少验证 | `sse.ts` 保留旧结构化事件类型；OpenAI/Qwen 配置和 fake e2e 命名仍在；是否可删需随新契约验证后决定 | P2 | 逐项证明不可达/被替代后删除，禁止盲删 |

## 当前里程碑边界

本轮第一个里程碑只修改外部动作本地投影及其直接测试/文档：远程调用仍在事务外；命令 CAS 成功后，命令状态、WorkflowRun、Turn 和状态 Item 在同一个本地事务中更新；旧 Lease 或版本竞争失败时不产生任何投影；状态 Item 使用结构化 envelope。完成后运行对应 Maven 测试、只读审查、显式路径暂存并提交，再更新 handoff 的下一步唯一动作。

## 外部验证边界

尚未发现可以据此安全执行破坏性 SQL 的专用测试数据库凭据。确认 MySQL 地址、数据库名和用户权限前不得清理或重建数据库；不得输出密钥。真实浏览器和 DeepSeek 验证若因服务或凭据不可用，必须记录为未验证，不能以替身测试代替。
