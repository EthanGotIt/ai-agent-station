# AI Agent Station 协作约定

这份文件只保留代码库无法可靠推断的长期约定。实现前先阅读邻近代码、测试和配置；新代码应延续已有风格，并以可读、简洁、可验证为目标。安全、架构边界和对外契约属于硬约束，其余细节按任务实际判断。

## 任务恢复

用户提出“继续当前任务”或类似请求时，如果 `docs/task-handoff.md` 为 `active`：

1. 阅读 handoff，随后查看 `git status --short` 与 `git diff --stat`。
2. 优先检查 handoff 指定的文件及相关 Diff；代码和 Git 状态优先于交接记录。
3. 从其中的“下一步唯一动作”继续。每完成一个可验证阶段，以当前状态覆盖 handoff，而不累积过程日志。

新任务或 `completed` handoff 不触发此流程。

## 架构边界（硬约束）

- 根包为 `cn.ethan`，项目使用 JDK 17。
- Maven 依赖保持 `app → core`、`app → infrastructure`、`infrastructure → core`。`core` 表达业务规则和端口，不依赖 Spring、数据库或模型供应商；`infrastructure` 适配外部系统；`app` 处理启动和 HTTP 装配。
- 模块内先按业务能力、再按职责组织，避免在同一层混用两种维度。`core` 使用 `model`、`enums`、`port`、`service`、`exception`、`support`；Workflow 使用 `workflow/model`、`node`、`engine`、`port`、`service` 与业务包。`infrastructure` 先以 `order`、`qwen`、`session`、`agentscope` 等能力分组，再使用 `entity`、`mapper`、`gateway`、`provider`、`store`、`validator`、`tool` 等职责包。`app` 保持 `config`、`controller`、`dto`、`handler`。
- 不创建空包、泛化 `impl` 包或独立 Workflow JAR。新增职责先落入现有矩阵；确实无法表达时，先说明原因并同步调整本文件。

## 命名与数据边界（硬约束）

- 接口按职责命名，如 `*Gateway`、`*Store`、`*Provider`、`*Executor`、`*Node`，不使用 `I` 前缀；实现通过技术或策略前缀区分，如 `LocalOrderGateway`、`AgentScopeReActExecutor`。
- Core 业务数据使用 `*Model`；数据库映射只在 infrastructure 的 `entity` 包中使用 `*Entity`；HTTP 请求、响应与 SSE 事件使用 `*Dto`。三者在边界转换，不跨层复用。
- Agent HTTP DTO 使用统一业务前缀和操作前缀，例如 `AgentChatRequestDto`、`AgentChatResponseDto`、`AgentChatEventDto`、`AgentCancelResponseDto`、`AgentErrorResponseDto`。`body` 只表达 `@RequestBody` 的参数位置，不作类型后缀。
- 枚举均以 `Enum` 结尾；服务、管理器、映射器、校验器、配置、控制器、异常处理器与异常分别使用 `*Service`、`*Manager`、`*Mapper`、`*Validator`、`*Configuration`、`*Controller`、`*ExceptionHandler`、`*Exception`。测试使用 `*Test`，集成测试使用 `*IT`。
- `*Utils` 只表示多个同主题的无状态方法，类为 `final` 且私有构造器。优先复用 JDK、Spring、Spring AI、MyBatis-Plus 与仓库现有能力；避免 `Common`、`Helper` 等模糊名称。
- 数据库库、表、列、索引与约束使用大写 `UPPER_SNAKE_CASE`；Java 字段、方法使用 `lowerCamelCase`。

## 编码方式

- 一个 Java 文件一个顶级类型，4 空格缩进、显式 import、清晰的空行和按语义换行。优先不可变 `record`、构造器注入、`List.copyOf` 与 `Map.copyOf`。
- 注释使用中文，解释设计原因或业务约束。公共类型和关键边界使用简短 JavaDoc；新建顶级类型沿用以下格式：

```java
/**
 * 类型职责：一句话说明该类型解决的问题和边界。
 *
 * @author ethan
 * @date 2026-08-06
 */
```

- 在能处理、转换或降级的边界捕获异常；无法恢复时交给统一异常边界。资源用 try-with-resources，`finally` 只做必需的状态收敛或释放，避免覆盖原始异常。日志记录稳定上下文，不记录密钥、完整 Prompt 或用户敏感数据。
- Controller 保持协议转换，格式校验在输入边界、业务不变量在 core。Spring Boot 只维护 `application.yml`；敏感配置通过环境变量注入且不提供非空默认值。超时、重试、队列限制和 TTL 均应可配置。
- 时间逻辑优先注入 `Clock`；远程 HTTP 配置连接与读取超时。数据库事务仅覆盖本地数据操作，不包裹模型调用或远程 HTTP。

## Agent 运行约束（硬约束）

- 聊天请求与 Workflow QuestionCard 回答均进入以 `userId + sessionId` 为键的有界 FIFO 队列；容量在 SSE 建连前判断。Conversation 历史、路由和执行在请求变为 ACTIVE 后解析。取消需要校验请求归属，并同时覆盖排队与执行中的请求。工具确认决定是对同一 ReAct 回合的旁路投递，不进入 FIFO，避免自锁。
- Router 使用 Spring AI 结构化输出与 Schema 校验；ReAct 使用 AgentScope 的 typed `streamEvents()`。Thinking 可以参与模型内部推理，但原始 Thinking 内容不进入 API、SSE 或日志。
- ReAct 工具明确声明只读性、并发安全性和外部执行属性；用户、会话、取消信息只从运行时上下文读取。HITL 仅属于 AgentScope ReAct 的 `ASK` 工具确认：原 SSE 连接接收 intervention，旁路接口提交决定，ReAct 使用相同运行时上下文继续；单次 ReAct 不持久化中断恢复，使用 `InMemoryAgentStateStore` 并在回合结束删除状态。支付、退款、发货、删除、账号变更等关键写入始终走确定性 Workflow，Workflow 不产生工具授权 HITL。
- Workflow 缺参和确认统一为持久化 `QuestionCard`；仅 `WAITING_USER_INPUT` 可由 `runId + questionId + checkpointId + expectedVersion + answers` 恢复，MySQL `WORKFLOW_RUN` 是事实来源。禁止重新引入 `pendingInputId`、旧 `/resume` 或按自由文本隐式恢复。
- 会话记忆的 Core 端口与 MySQL 适配器必须按 `userId + sessionId` 隔离。首期默认关闭且只记录最终 ReAct / 已完成 Workflow 结果，不注入 Router、Workflow 或 ReAct；提供查询、编辑和软删除。跨会话或跨用户检索必须另行设计、评审和验证。
- 优先使用 MyBatis-Plus `BaseMapper` 处理 CRUD 与条件查询；仅在定制 SQL 有明确价值时增加 Mapper XML，并显式使用大写物理名称。

## 验证

完成变更后运行以下检查；失败时修复根因，不以跳过测试或降低约束收尾：

```text
python -m scripts.convention_check
python -m unittest discover -s scripts/tests -p "test_*.py"
mvn clean '-DskipTests=false' test
```
