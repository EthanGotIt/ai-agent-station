# AI Agent Station 协作约定

这份文件只保留代码库无法可靠推断的长期约定。实现前先阅读邻近代码、测试和配置；新代码应延续已有风格，并以可读、简洁、可验证为目标。安全、架构边界、对外契约和 Git 提交边界属于硬约束，其余细节按任务实际判断。

## 任务恢复

用户提出“继续当前任务”或类似请求时，如果 `docs/task-handoff.md` 为 `active`：

1. 阅读 handoff，随后查看 `git status --short` 与 `git diff --stat`。
2. 优先检查 handoff 指定的文件及相关 Diff；代码和 Git 状态优先于交接记录。
3. 从“下一步唯一动作”继续。每完成一个可验证阶段，以当前状态覆盖 handoff，不累积过程日志。

新任务或 `completed` handoff 不触发此流程。

## 架构边界（硬约束）

- 根包为 `cn.ethan`，项目使用 JDK 17。
- Maven 依赖保持 `app → core`、`app → infrastructure`、`infrastructure → core`。`core` 只表达业务规则和端口，不依赖 Spring、数据库或模型供应商；`infrastructure` 适配外部系统；`app` 处理启动和 HTTP 装配。
- v3 的 Agent 上下文根为 `Thread`，执行层次为 `Thread → Turn → Item`。登录上下文不持有 Agent 历史；不实现 Thread Fork、分支合并、跨上下文自动记忆或多 Agent 协作。
- 协调 Agent 统一使用 Spring AI Tool Calling。只读查询可以调用 Tool；退款、催发货、删除和其他外部写操作必须启动确定性 Workflow，不允许模型直接产生外部副作用。
- Workflow 的 QuestionCard、Checkpoint、WorkflowRun 和 ExternalActionCommand 必须持久化；远程调用不得包在本地数据库事务内。
- 模块内先按业务能力、再按职责组织。Core 使用 `model`、`enums`、`port`、`service`、`exception`、`support`；Workflow 使用 `workflow/model`、`node`、`engine`、`port`、`service`；Infrastructure 使用 `entity`、`mapper`、`gateway`、`provider`、`store`、`validator`、`tool`；App 保持 `config`、`controller`、`dto`、`handler`。
- 不创建空包、泛化 `impl` 包或独立 Workflow JAR。新增职责无法放入现有矩阵时，先说明原因并同步调整本文件。

## 命名与数据边界（硬约束）

- 接口按职责命名，如 `*Gateway`、`*Store`、`*Provider`、`*Executor`、`*Node`，不使用 `I` 前缀；实现通过技术或策略前缀区分。
- Core 业务数据使用 `*Model`；数据库映射只在 Infrastructure 的 `entity` 包中使用 `*Entity`；HTTP 请求、响应和 SSE 事件使用 `*Dto`。三者必须在边界转换，不跨层复用。
- Agent DTO 使用统一业务前缀和操作前缀，例如 `AgentThreadCreateRequestDto`、`AgentTurnAcceptedResponseDto`、`AgentThreadEventDto`、`AgentErrorResponseDto`。`body` 只表达 `@RequestBody` 参数位置，不作类型后缀。
- 枚举以 `Enum` 结尾；服务、管理器、映射器、校验器、配置、控制器、异常处理器和异常分别使用 `*Service`、`*Manager`、`*Mapper`、`*Validator`、`*Configuration`、`*Controller`、`*ExceptionHandler`、`*Exception`。测试使用 `*Test`，集成测试使用 `*IT`。
- `*Utils` 只表示同一主题的多个无状态方法，类型必须是 `final` 并有私有构造器。避免 `Common`、`Helper` 等模糊名称。
- 数据库库、表、列、索引和约束使用大写 `UPPER_SNAKE_CASE`；Java 字段和方法使用 `lowerCamelCase`。

## 编码、注释与安全（硬约束）

- 一个 Java 文件只能有一个顶级类型；4 空格缩进、显式 import、清晰空行；优先不可变 `record`、构造器注入、`List.copyOf` 和 `Map.copyOf`。
- 注释使用中文，解释设计原因、状态约束、幂等边界或降级策略，不复述代码。所有保留或新增的公共类型、端口、Controller、Store、Workflow、Worker 和关键边界必须有简短 JavaDoc。
- 新建顶级类型沿用：

```java
/**
 * 类型职责：一句话说明该类型解决的问题和边界。
 *
 * @author ethan
 * @date 2026-08-19
 */
```

- 在能处理、转换或降级的边界捕获异常；无法恢复时交给统一异常边界。资源使用 try-with-resources；日志只记录稳定上下文，不记录密钥、完整 Prompt、原始 Thinking 或用户敏感数据。
- Controller 只做协议转换；格式校验在输入边界，业务不变量在 Core。Spring Boot 只维护 `application.yml`；敏感配置通过环境变量注入且不提供非空默认值。
- 时间逻辑优先注入 `Clock`；远程 HTTP 必须配置连接和读取超时。数据库事务只覆盖本地数据操作，不包裹模型调用或远程 HTTP。

## Agent 运行约束（硬约束）

- 聊天 Turn 和 Workflow QuestionCard 回答均进入以 `threadId` 为键的有界 FIFO；同一 Thread 严格串行，不同 Thread 可以并行。队列容量、等待、Turn、Tool、外部动作和 SSE 心跳超时均可配置。
- 排队 Turn 可直接取消；ACTIVE Turn 通过运行上下文协作取消。取消不回滚已提交外部副作用，只停止后续步骤并展示实际状态。
- 同一 Thread 最多一个未回答 QuestionCard。普通消息在等待回答时返回 `THREAD_AWAITING_ANSWER`；回答作为新的 Turn 入队，并携带 `runId`、`questionId`、`checkpointId`、`expectedVersion` 和结构化 answers。
- Item 是可恢复事实，按 Thread 内单调 Sequence 持久化。SSE 只负责实时体验；断线先从 Items 恢复，再重新订阅，不要求重放丢失的文本增量。
- 不持久化或展示原始 Thinking。Tool Call、Tool Result、Workflow、外部动作、错误和最终消息必须以受控 Item/事件记录。
- 外部写操作统一使用 ExternalActionCommand，必须具备幂等键、Lease、有限退避重试和人工重试；Worker 重跑不得产生第二次业务写入。
- 用户身份只从认证上下文取得，不能信任请求体中的 `userId`；演示 Header 也必须在统一边界解析，Controller 不直接拼装身份。

## Git 提交守则（硬约束）

- 跨多个子系统的计划必须拆成多个可独立验证、可回滚的阶段提交，禁止将完整计划压成一个大提交。
- 一个提交只表达一个主要意图，不混合架构重构、前端改造、历史清理和无关格式化。行为变化必须与对应测试同提交；契约变化必须同步更新文档。
- 提交前运行该阶段的最小验证；提交后仓库应保持可构建，或至少通过提交说明中明确的模块测试。
- 使用仓库既有 Conventional Commit 风格：`feat:`、`fix:`、`refactor:`、`test:`、`docs:`、`chore:`。
- 阶段性修复优先追加到对应阶段；允许合并同一阶段的 `fixup`，但禁止把所有阶段重新合并为一个提交。
- 禁止使用 `git add -A` 把用户已有工作区改动带入提交。必须按明确路径暂存，并在提交前检查 staged diff 和 `git diff --cached --check`。
- `.idea`、deployment、Docker、Hook 等用户已有无关改动不得进入本计划任何提交。纯验证不创建空提交。
- 推荐阶段边界：架构守则 → 持久化 → Runtime/Context → Spring AI Tool → Workflow/Worker → API/SSE → 前端 → 旧代码清理 → SQL/脚本/文档 → 测试验收。

## 验证

完成变更后运行以下检查；失败时修复根因，不以跳过测试或降低约束收尾：

```text
python -m scripts.convention_check
python -m unittest discover -s scripts/tests -p "test_*.py"
mvn clean '-DskipTests=false' test
npm run typecheck
npm test -- --run
npm run build
```
