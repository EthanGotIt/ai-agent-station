# Spring AI 2.0 升级说明

## 状态

当前分支：`codex/spring-ai-2-tool-advisor-refactor`

升级范围：Spring AI `1.1.7 -> 2.0.0`、Spring Boot `3.5.14 -> 4.1.0`、MyBatis Spring Boot Starter `3.0.4 -> 4.0.1`。

状态：已完成代码适配、全量测试、打包和 live smoke。

## 技术评估

本次升级采用 Spring AI `2.0.0` 正式版，不使用 milestone 或 snapshot。官方版本与迁移依据：

- [Spring AI 2.0.0 Release](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0)
- [Spring AI Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)
- [Spring AI Reference](https://docs.spring.io/spring-ai/reference/index.html)
- [Spring Boot 4.1.0 Release](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.0)

当前分支已经进入 Spring AI 2 主链路收敛：模型调用改由 `SpringAiAgentRuntime -> ChatClient -> Advisor Chain` 承接，旧 Harness 主执行链路已删除。

## 主要适配

### OpenAI 模型装配

- 删除 Spring AI 1.x 的 `OpenAiApi` 依赖。
- API 装配节点保存不可变 `OpenAiChatOptions`，统一承载 `baseUrl` 和 `apiKey`。
- 模型装配节点使用 `apiOptions.mutate().model(...).build()` 派生模型配置，避免重建时遗漏连接参数。
- Embedding 使用 `OpenAiEmbeddingOptions` 直接配置连接信息、模型和维度。
- 删除数据库、VO、PO 和 MyBatis 中失效的 `completions_path / embeddings_path` 字段；Spring AI 2 的官方 OpenAI Java SDK 不再提供这两个独立路径入口。

### 动态工具调用

- 不在运行时复制 `ChatClient`，继续复用已装配客户端。
- MCP ToolCallback 通过 Spring AI 2 `ToolCallingAdvisor`、`DefaultToolCallingManager` 和 `ToolCallbackResolver` 进入主链路。
- `ChatClient` 调用时由 `ToolCallingAdvisor` 接管工具执行循环，因此模型首轮返回 tool call 后，会执行回调并继续下一轮模型调用。
- `SpringAi2CompatibilityTest` 使用脚本化 ChatModel 验证了“模型请求工具 -> 回调执行 -> 模型最终回答”的完整闭环。
- 自定义 MCP 关键词打分路由退出主链路，工具发现和调用循环交给 Spring AI 2 Tool Calling；项目保留注册边界、只读过滤和 `GuardedToolCallback` 安全包装。

### Advisor Chain 与 Session

- 新增 `SpringAiAgentRuntime` 作为主执行链路，`AgentDispatchService` 默认调用该 Runtime。
- 新增 `ContextBudgetAdvisor`、`EvidenceRetrievalAdvisor` 和 `ObservationTraceAdvisor`，分别承担上下文预算、项目知识 evidence 检索和运行态 trace。
- 引入 Spring AI Community `spring-ai-session`，并通过 `SessionMemoryAdvisor` 接入 Advisor Chain。
- Spring AI 2.0.0 GA 本体没有在 `org.springframework.ai.chat.memory` 下暴露 `Session / SessionEvent / SessionService / CompactionTrigger / CompactionStrategy`，当前使用社区 `org.springframework.ai.session.*` API 作为面向 Spring AI 2.1 记忆方向的前置兼容路径。
- 当前默认使用 `InMemorySessionRepository` 验证 Session Advisor 语义，现有 conversation 表继续作为跨重启兜底；JDBC Session 存储和表结构迁移后续单独处理。

### MCP 与 Actuator

- MCP SDK 升级到 2.x 后，Stdio transport 改用 `JacksonMcpJsonMapperSupplier`。
- Streamable HTTP header 注入迁移为 `httpRequestCustomizer(...)`。
- Boot 4 的健康检查接口迁移到 `org.springframework.boot.health.contributor`。

### 依赖健康

- MySQL 驱动改为 `com.mysql:mysql-connector-j`，版本由 Spring Boot 统一管理。
- Web starter 改为 Boot 4 推荐的 `spring-boot-starter-webmvc`，不继续使用已废弃的 `spring-boot-starter-web`。
- 删除应用模块重复声明的 Tomcat core。
- 删除子模块重复 compiler 配置，统一使用根 POM 的 Java 17 `release`。
- Surefire 版本交给 Spring Boot Parent 管理，保留项目原有测试筛选和 `skipTests` 开关语义。
- 增加 JUnit Vintage Engine，让现有 JUnit 4 测试在 Boot 4 管理的 JUnit Platform / Surefire 3 上继续真实执行，避免出现构建成功但测试数为 0 的假绿。

## Spring AI 2 新特性应用结论

本次直接采用的能力：

- **统一 Tool Calling Advisor**：动态 MCP 回调改用 Spring AI 2 `ToolCallingAdvisor` 和 `ToolCallbackResolver`，既能接收 ToolCallback，也保留后续接入 Provider 或 `@Tool` 对象的统一入口。
- **ChatClient ToolCallingAdvisor**：工具执行循环由 Spring AI 2 的 Advisor 负责，项目只保留路由、授权和异常治理，不再自研模型/工具循环。
- **Advisor Chain 主链路**：上下文预算、Session 记忆、RAG evidence 和运行态观测开始进入 Spring AI Advisor 体系，旧 Harness 不再作为新增能力入口。
- **社区 Session API**：接入 Spring AI Community `spring-ai-session` 的 `SessionService / SessionMemoryAdvisor / CompactionTrigger / CompactionStrategy` API，可向 Spring AI 2.1 的记忆方向平滑迁移。
- **不可变 Provider Options**：连接级 Options 缓存后通过 `mutate()` 派生模型配置，降低动态装配时遗漏 base URL、API key 或模型参数的风险。
- **Builder 风格文本切分器**：`TokenTextSplitter.builder()` 代替已标记移除的构造器，为后续按知识库配置 chunk 参数保留稳定扩展点。
- **MCP SDK 2 transport API**：使用 Jackson 3 mapper supplier 和新的 HTTP request customizer，继续支持 Stdio 与 Streamable HTTP。

评估后暂缓的能力：

- **Provider 原生结构化输出与 schema retry**：适合 Action JSON，但当前百炼 OpenAI compatible 模型支持边界需要单独验证，而且现有模型端口统一承担 trace、上下文预算和 fallback；本阶段不为此增加第二种模型调用入口。
- **JDBC Session 存储直接替换现有会话表**：当前只做 Session Advisor 语义接入，持久化迁移需要单独设计表兼容和历史数据读取。
- **更多 ToolCallbackProvider 自动装配**：当前 MCP 必须经过注册边界和只读授权，不能为了减少装配代码绕过治理层。

结论是只吸收能减少自研逻辑、消除废弃 API 或增强扩展性的特性，不把框架升级包装成新的 Agent 业务能力。

## 兼容边界

- 当前 Chat 和 Embedding 仍通过 DashScope OpenAI compatible endpoint 接入。
- 自定义 `completions_path / embeddings_path` 不再支持；供应商地址必须配置为完整 compatible base URL，例如 `/compatible-mode/v1`。
- 项目业务 JSON 处理暂未整体迁移，MCP transport 已按 SDK 2 要求使用 Jackson 3；不为追求依赖形式统一扩大业务重构。
- Spring AI 2 升级不等于增加新的 Agent 能力，简历和答辩应以 Spring AI 2 Advisor Chain 下的 evidence 治理、MCP 工具安全和 Session 短期记忆为主线。

## 验收命令

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=SpringAi2CompatibilityTest,SessionApiAvailabilityTest,SpringAiAdvisorInfrastructureTest" test
mvn -q "-DskipTests=false" test
mvn -q "-DskipTests" package
git diff --check
```

运行态 smoke 继续使用：

```powershell
.\scripts\dev\run-local-smoke.ps1
```

## 验收记录

- 兼容性测试：`SpringAi2CompatibilityTest` 2 个测试通过，覆盖 Options 派生和模型/工具/模型调用闭环。
- 全量测试：160 个测试，147 个实际执行，13 个真实 AI 或数据库变更门禁测试按设计跳过，0 failures，0 errors。
- 打包：Spring Boot 4.1.0 可执行 jar 构建通过。
- 运行态：`/actuator/health` 返回 HTTP 200，MySQL、PGVector、Elasticsearch、Context7 Stdio MCP 和 Exa Streamable HTTP 均完成初始化。
- live smoke：当前 Spring AI 2 主链路的本地 smoke 与三组 evaluation 仍需单独补充，不用旧 Harness 结果替代。
- 已停止 Windows 本地 PostgreSQL，并将启动类型调整为手动；项目按默认 `5432` 直接连接 Docker pgvector，与 Docker Elasticsearch 统一管理。
- 开发、导入和 smoke 脚本新增本地 PostgreSQL 冲突检查，避免应用静默连接到错误实例。
- 本轮真实输出长度未达到 `context_guard` 阈值；压缩触发与保留策略由 `AgentContextWindowServiceTest` 确定性覆盖。
