# Commerce Guardian Agent 协作约定

这份文件只保留代码库无法可靠推断的长期约定。实现前先阅读邻近代码、测试和配置；新代码应延续已有风格，并以可读、简洁、可验证为目标。安全、架构边界、对外契约和 Git 提交边界属于硬约束，其余细节按任务实际判断。与全局默认约定冲突时，以本文件中更具体的项目规则为准。

## 任务恢复

`AGENTS.md` 是项目长期协作规范，保留完整的边界、命名、安全、提交和验证规则；优化时只做可审阅的定点调整，不按 `docs/task-handoff.md` 的机制覆盖或压缩它。

用户提出“继续当前任务”或类似请求时，如果 `docs/task-handoff.md` 为 `active`：

1. 阅读 handoff，随后查看 `git status --short` 与 `git diff --stat`。
2. 优先检查 handoff 指定的文件及相关 Diff；代码和 Git 状态优先于交接记录。
3. 从“下一步唯一动作”继续。每完成一个可验证阶段，以当前状态覆盖 handoff，不累积过程日志。

新任务或 `completed` handoff 不触发此流程。

### handoff 摘要更新机制

- `docs/task-handoff.md` 是有界的最新状态快照，不是提交记录、聊天记录或测试日志；只保留恢复任务所需的最小事实。
- 在以下时机覆盖更新：完成一个可独立验证的阶段；下一步唯一动作发生变化；出现、解除或改变阻塞；准备跨轮次/上下文压缩/暂停工作；提交或外部验收改变了可恢复状态。普通的单次命令执行不触发更新。
- 压缩时保留 `Goal`、仍有效的 `Completed`、当前 `Decisions`、唯一 `TODO`、具体 `Blocked`、最近的 `Validation` 和 `Preserve`；删除已被新结果替代的细节、重复说明和过程日志。详细证据放入实施追踪、验收文档、测试产物或 Git 历史。
- 更新必须整体覆盖文件并刷新 `status`、`updated`；不得通过追加历史段落来“压缩”。`active` 必须给出可执行的 `Next action`，只有完整验收且没有必需工作时才改为 `completed`。
- 恢复时把摘要当作导航而非事实源：先按本节第 1、2 步核对代码和 Git 状态，再从唯一下一步继续；若摘要与代码冲突，以代码、测试、运行状态和 Git 记录为准，并在同一阶段结束时重新覆盖摘要。

## 架构边界（硬约束）

- 根包为 `cn.ethan`，项目使用 JDK 17。
- Maven 依赖保持 `app → core`、`app → infrastructure`、`infrastructure → core`。`core` 只表达业务规则和端口，不依赖 Spring、数据库或模型供应商；`infrastructure` 适配外部系统；`app` 处理启动和 HTTP 装配。
- Commerce Guardian Agent 的上下文根为 `Thread`，执行层次为 `Thread → Turn → Item`。登录上下文不持有 Agent 历史；不实现 Thread Fork、分支合并、跨上下文自动记忆或多 Agent 协作。
- 协调 Agent 统一使用 Spring AI Tool Calling。只读查询可以调用 Tool；退款、催发货、删除和其他外部写操作必须启动确定性 Workflow，不允许模型直接产生外部副作用。
- Workflow 的 QuestionCard、Checkpoint、WorkflowRun 和 ExternalActionCommand 必须持久化；远程调用不得包在本地数据库事务内。
- 模块内先按 Agent 能力、再按具体技术边界组织。Core 使用 `agent.thread`、`agent.execution`、`agent.context`、`agent.coordination`、`agent.workflow`、`agent.action`、`agent.event` 和 `commerce.order`；Infrastructure 使用对应能力下的 `persistence`、`springai`、`worker`、`fixture`、`http` 等适配器包；App 使用 `bootstrap`、`agent.api` 和 `agent.stream`。不再创建顶层 `model`、`service`、`port`、`entity`、`mapper`、`gateway`、`controller`、`dto` 或 `handler` 技术大包。
- 能力是第一分包维度，技术实现只位于能力包的叶子位置。小型能力的模型、端口和服务可以同包；只有存在清晰生命周期或技术边界时才建立 `persistence`、`springai`、`http`、`worker`、`stream` 子包。Core 能力之间必须通过显式端口交互，禁止反向依赖 Infrastructure 或 App。
- 不创建空包、泛化 `impl` 包或独立 Workflow JAR。新增职责无法放入现有矩阵时，先说明原因并同步调整本文件。

## 命名与数据边界（硬约束）

- 接口按职责命名：`*Gateway` 表示外部或跨能力边界，`*Store` 表示本地事实持久化，`*Executor` 执行外部动作，`*Coordinator` 负责单 Turn 协调，`*Engine` 负责确定性 Workflow，`*Subscription` 表示实时订阅；不使用 `I` 前缀，具体实现通过技术或策略前缀区分。
- Core 的持久业务状态使用 `*Model`；端口内部的 `Command`、`Result`、`Draft` 等操作值使用语义名称即可。数据库映射类型统一位于 Infrastructure 的 `<capability>.persistence` 叶子包并使用 `*Entity`、`*Mapper`；HTTP 请求、响应和 SSE 事件使用 `*Dto`。三者必须在边界转换，不跨层复用。
- Entity、Mapper 和 MyBatis Store 属于同一能力的 `persistence` 叶子包；实现类使用 `Mybatis`、`SpringAi`、`Http`、`Local`、`InMemory` 等技术或策略前缀，避免用 `Impl` 隐藏实现边界。
- Agent DTO 使用统一业务前缀和操作前缀，例如 `AgentThreadCreateRequestDto`、`AgentTurnAcceptedResponseDto`、`AgentThreadEventDto`、`AgentErrorResponseDto`。`body` 只表达 `@RequestBody` 参数位置，不作类型后缀。
- 枚举以 `Enum` 结尾；只有表达完整用例边界的类型才使用 `*Service`，其他职责分别使用 `*Manager`、`*Mapper`、`*Validator`、`*Configuration`、`*Controller`、`*ExceptionHandler`、`*Exception`。队列调度使用 `*Queue`，上下文组装使用 `*Assembler`，租约任务使用 `*Worker`。测试使用 `*Test`，集成测试使用 `*IT`。
- `*Utils` 只表示同一主题的多个无状态方法，类型必须是 `final` 并有私有构造器。避免 `Common`、`Helper` 等模糊名称。
- 数据库库、表、列、索引和约束使用大写 `UPPER_SNAKE_CASE`；Java 字段和方法使用 `lowerCamelCase`。

## 项目与文档命名（硬约束）

- 对外产品名统一写作 `Commerce Guardian Agent`；正文、页面标题、日志提示和示例不得再使用旧项目名或版本号作为产品名。
- Maven 根项目、模块目录、ArtifactId、前端目录和 npm package 使用小写 kebab-case：`commerce-guardian-agent`、`commerce-guardian-agent-core`、`commerce-guardian-agent-infrastructure`、`commerce-guardian-agent-app`、`agent-fronted`。
- 文档文件名使用小写 kebab-case，表达稳定职责，不在文件名中编码版本号或日期；例如 `architecture.md`、`runbook.md`、`task-handoff.md`。SQL 基线命名为 `commerce-guardian-agent.sql`。
- Java 类型仍使用 PascalCase，Python 模块使用 snake_case，前端组件使用 PascalCase；目录名遵循所属生态的标准命名。
- 修改项目名时必须同步检查构建文件、运行配置、数据库名、前端元数据、脚本提示、文档链接和验收示例；不得留下旧项目名或版本标签的功能性引用。

## 编码、注释与安全（硬约束）

- 一个 Java 文件只能有一个顶级类型；4 空格缩进、显式 import、清晰空行；优先不可变 `record`、构造器注入、`List.copyOf` 和 `Map.copyOf`。
- 注释使用中文，解释设计原因、状态约束、幂等边界或降级策略，不复述代码。所有保留或新增的公共类型、端口、Controller、Store、Workflow、Worker 和关键边界必须有简短 JavaDoc。
- 新建顶级类型沿用：

```java
/**
 * 类型职责：一句话说明该类型解决的问题和边界。
 *
 * @author ethan
 * @date YYYY-MM-DD
 */
```

其中 `@date` 使用文件创建当天日期；不得仅因格式整理而改写既有类型日期。

- 在能处理、转换或降级的边界捕获异常；无法恢复时交给统一异常边界。资源使用 try-with-resources；日志只记录稳定上下文，不记录密钥、完整 Prompt、原始 Thinking 或用户敏感数据。
- Controller 只做协议转换；格式校验在输入边界，业务不变量在 Core。Spring Boot 只维护 `application.yml`；敏感配置通过环境变量注入且不提供非空默认值。
- 时间逻辑优先注入 `Clock`；远程 HTTP 必须配置连接和读取超时。数据库事务只覆盖本地数据操作，不包裹模型调用或远程 HTTP。
- Windows/JDK 的 loopback、临时目录或网络栈问题属于宿主机运行环境；只能按运行手册和用户级环境排查，禁止为绕过它修改项目 HTTP 实现、协议或生产代码。

## Agent 运行约束（硬约束）

- 聊天 Turn 和 Workflow QuestionCard 回答均进入以 `threadId` 为键的有界 FIFO；同一 Thread 严格串行，不同 Thread 可以并行。队列容量、等待、Turn、Tool、外部动作和 SSE 心跳超时均可配置。
- 排队 Turn 可直接取消；ACTIVE Turn 通过运行上下文协作取消。取消不回滚已提交外部副作用，只停止后续步骤并展示实际状态。
- 同一 Thread 最多一个未回答 QuestionCard。普通消息在等待回答时返回 `THREAD_AWAITING_ANSWER`；回答作为新的 Turn 入队，并携带 `runId`、`questionId`、`checkpointId`、`expectedVersion` 和结构化 answers。
- Item 是可恢复事实，按 Thread 内单调 Sequence 持久化。SSE 只负责实时体验；断线先从 Items 恢复，再重新订阅，不要求重放丢失的文本增量。
- 不持久化或展示原始 Thinking。Tool Call、Tool Result、Workflow、外部动作、错误和最终消息必须以受控 Item/事件记录。
- 外部写操作统一使用 ExternalActionCommand，必须具备幂等键、Lease、有限退避重试和人工重试；Worker 重跑不得产生第二次业务写入。
- 前端状态必须从持久化 Item 投影：`WORKFLOW_RESULT` 和 `EXTERNAL_ACTION_STATUS` 表达业务结果，不能让回答/决策子 Turn 的 `TURN_STATE=COMPLETED` 覆盖失败、事实变化或等待状态；多个折叠动作按各自 Turn/Run 关联，不使用 Thread 级全局成功标记。
- Item 历史分页和 SSE 回放只有在游标严格前进时才继续；服务端或客户端遇到重复、乱序、无效页必须收口，不能因 `hasMore` 失真而无限请求。
- 用户身份只从认证上下文取得，不能信任请求体中的 `userId`；演示 Header 也必须在统一边界解析，Controller 不直接拼装身份。

## Code Review Rules

- 外部写操作必须由持久化 Workflow、Checkpoint 和 ExternalActionCommand 授权与执行；模型文本或只读 Tool 结果不得直接产生退款、催发货、删除等副作用。
- `WORKFLOW_RESULT` 与 `EXTERNAL_ACTION_STATUS` 是业务结果的权威投影；任何 `TURN_STATE=COMPLETED` 都不能把失败、事实变化或等待状态覆盖成成功。
- Item 分页与 SSE 回放的游标必须严格前进；重复、乱序、无效或不前进的页必须安全收口，不能因 `hasMore` 失真而无限重试。

## Git 提交守则（硬约束）

- 只有用户在当前任务中明确要求创建提交时才执行暂存或提交；未授权时只按可独立验证、可回滚的阶段组织改动，不自行执行 `git add` 或 `git commit`。
- 跨多个子系统的计划必须拆成多个可独立验证、可回滚的阶段提交，禁止将完整计划压成一个大提交。
- 一个提交只表达一个主要意图，不混合架构重构、前端改造、历史清理和无关格式化。行为变化必须与对应测试同提交；契约变化必须同步更新文档。
- 提交前运行该阶段的最小验证；提交后仓库应保持可构建，或至少通过提交说明中明确的模块测试。
- 使用仓库既有 Conventional Commit 风格：`feat:`、`fix:`、`refactor:`、`test:`、`docs:`、`chore:`。
- 阶段性修复优先追加到对应阶段；允许合并同一阶段的 `fixup`，但禁止把所有阶段重新合并为一个提交。
- 禁止使用 `git add -A` 把用户已有工作区改动带入提交。必须按明确路径暂存，并在提交前检查 staged diff 和 `git diff --cached --check`。
- `.idea`、deployment、Docker、Hook 等用户已有无关改动不得进入本计划任何提交。纯验证不创建空提交。
- 仓库不依赖 `.hooks` 或 `.githooks` 执行审查；本地只运行显式门禁，代码审查和合并门禁以推送后的 GitHub PR/CI 为准，不在项目脚本中自动 push。
- 推荐阶段边界：架构守则 → 持久化 → Runtime/Context → Spring AI Tool → Workflow/Worker → API/SSE → 前端 → 旧代码清理 → SQL/脚本/文档 → 测试验收。

## 分支治理（硬约束）

- GitHub `codex/commerce-guardian-agent` 是默认分支和持续迭代主力；新功能分支从它创建，并以它为 PR base。
- 通过本地门禁的小型聚焦提交可以直接进入主力分支；跨模块、高风险或需要审查的改动使用临时 `codex/*` 分支和 PR 回主力分支。
- GitHub `master` 只保存明确确认的里程碑；发布时从主力分支创建 `codex/milestone-*` promotion 分支并创建 PR，不直接推送、强推、删除，也不把 `master` 反向合入主力分支。
- 已完成或归档的远端分支默认保留，只有用户明确要求清理时才删除；同步历史改动时记录来源提交和排除原因。

## 验证

按变更范围运行最小充分的检查；跨模块、运行时、契约或发布准备变更必须运行完整检查矩阵。纯文档或配置变更只运行直接相关的格式、解析或一致性检查，并在交付时说明未运行的项目。失败时修复根因，不以跳过测试或降低约束收尾。

```text
python -m scripts.convention_check
python -m unittest discover -s scripts/tests -p "test_*.py"
mvn clean '-DskipTests=false' test
npm --prefix agent-fronted run typecheck
npm --prefix agent-fronted test
npm --prefix agent-fronted run build
```
