# AI Agent Station 项目说明

## 当前基线

- 运行基线：Spring Boot 4.1.0、Spring Framework 7、Spring AI 2.0.0、JDK 17。
- 当前主执行链路：`AiAgentController -> AgentDispatchService -> SpringAiAgentRuntime`。
- 旧 Harness 链路已标记为迁移期兼容路径，不再作为新增能力入口。
- Flow Plan 已退出主执行链路，不要重新把 `Run -> Plan -> Step` 作为主执行模型。
- 当前迁移方向是 Spring AI 2 优先：`ChatClient + Advisor Chain + Tool Calling + MCP + Session`。

## Spring AI 2 兼容性结论

- `ChatClient` 的请求级 `tools(...)` 能力可用，并由 `SpringAi2CompatibilityTest` 覆盖。
- `ToolCallingAdvisor`、`MessageChatMemoryAdvisor`、`StructuredOutputValidationAdvisor`、`ToolCallbackResolver` 和 MCP ToolCallback provider 在 Spring AI 2.0.0 中可用。
- Spring AI 2.0.0 GA 本体没有在 `org.springframework.ai.chat.memory` 下暴露 `Session`、`SessionEvent`、`SessionService`、`CompactionTrigger` 或 `CompactionStrategy`。
- Spring AI Community `spring-ai-session` 模块提供 `org.springframework.ai.session.Session`、`SessionEvent`、`SessionService`、`SessionMemoryAdvisor` 以及压缩触发器和压缩策略 API，本分支优先沿这条路径迁移记忆。
- `ChatMemory / ChatMemoryRepository` 只作为社区 Session 依赖不可用或不兼容时的兜底，不在本轮继续扩展自研 Session 框架。

## 迁移方向

- 保留 Harness 中真正有价值的业务治理能力：证据策略、引用校验、证据不足拒答、运行态复盘和 SSE 可观测性。
- 模型调用统一收敛到 Spring AI `ChatClient` 网关。
- Prompt、上下文、记忆、RAG、工具和观测逻辑逐步迁移到 Advisor Chain。
- 自定义 MCP 关键词路由退出主链路，工具发现和调用交给 Spring AI 2 Tool Calling、ToolCallbackResolver 与 MCP 集成。
- 保留 `GuardedToolCallback` 或等价安全包装，负责脱敏、风险阻断、结果限长、结构化错误和工具调用采集。
- 不宣称 Spring AI Core 原生 Session 已落地；对外表述应说明当前使用 Spring AI Community `spring-ai-session`，作为面向 Spring AI 2.1 记忆方向的前置兼容路径。

## 代码健康规则

- 不新增 Flow Plan 类，也不新增自定义 MCP 路由器变体。
- 不继续膨胀 `AgentModelPort`，模型调用能力应收敛到 ChatClient 网关。
- 新增 Spring AI Advisor 基础设施要小步、可测、可替换。
- 默认单测不得依赖 Docker、网络、API key 或 MCP 子进程。
- Live evaluation 分层执行：日常只跑 quick，阶段验收和简历效果结论才跑 full。
