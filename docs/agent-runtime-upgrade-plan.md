# AI Agent Station Agent Runtime 升级总控方案

## 当前进度

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| Phase 1：Run / Step 执行模型 | 已完成 | 增加运行态追踪、终止和复盘 |
| Phase 2：Tool Calling 治理 | 已完成 | 增加 MCP 动态路由、风险拦截和异常归一化 |
| Phase 3：Agentic RAG | 已完成 | 增加 Hybrid RAG evidence |
| Phase 4：上下文边界 | 已完成 | 明确 session、规则和偏好边界 |
| Phase 5：整体收口 | 已完成 | 完成旧 Runtime 验收 |
| Phase 6：MCP 执行时注入 | 已完成 | 工具由执行阶段自主决策 |
| Phase 7：持久化 Session 短期记忆 | 已完成 | 验证旧方案的消息持久化闭环 |
| Phase 8：Spring AI Alibaba GraphRuntime | 已完成 | 真实模型、MCP 路由、BM25 降级、checkpoint 和 cancel smoke 已验收 |
| Phase 9：百炼 Embedding 与 MCP 生命周期收敛 | 已完成 | 切换 `text-embedding-v4`，移除旧 Embedding 遗留，消除 MCP 全量串行冷启动 |

## 项目变化

Phase 8 直接替换旧 FlowRuntime，不保留双执行路径：

```text
旧：自研 JSON Plan -> Step1-6 -> 自定义上下文压缩 -> MySQL 消息双写
新：ReactAgent -> PostgresSaver -> SummarizationHook -> 动态 MCP / rag_search
```

保留的自定义价值：

- MCP 动态路由和 Tool Guard。
- PGVector + Elasticsearch BM25、RRF、Small-to-Big 和 evidence。
- MySQL Run / Step 审计。
- 动态 Prompt、Model、ToolCallback 装配。

删除的自研负担：

- JSON Plan parser、validator 和节点 Prompt。
- Step1-6 Flow 链。
- 自研上下文预算、压缩器和 session 消息双写。
- `ai_agent_conversation_message` 和 `ai_agent_flow_config`。
- 未再参与执行的 advisor 动态装配、DAO 和 `ai_client_advisor`。
- 绕过平台直连模型和 MCP 的旧手工实验测试。

## Phase 8 技术评估

采用：

- Spring AI Alibaba `spring-ai-alibaba-agent-framework:1.1.2.3`
- Spring AI Alibaba `spring-ai-alibaba-starter-document-parser-markdown:1.1.2.3`
- Spring AI Alibaba `spring-ai-alibaba-dashscope:1.1.2.3`

保持：

- Spring Boot `3.5.14`
- Spring AI `1.1.7`
- MCP SDK `0.18.2`

依赖门禁：

- Alibaba Graph 传递依赖中的 Spring AI 已由根 BOM 统一到 `1.1.7`。
- 排除 Oracle、MySQL 9、Redis、Mongo 和旧 MCP 传递依赖。
- 保留 DeepSeek 和 ZhipuAI 消息兼容模块：官方 checkpoint serializer 初始化时会直接注册对应的 AssistantMessage 类型。
- Markdown parser starter 将 `DocumentParser` API 声明为 optional，因此显式增加官方 dashscope 模块。

不采用：

- 双 Runtime 并行。
- 多 Agent handoff。
- Store 长期记忆。
- 精确 Qwen tokenizer。
- HITL、Sandbox、Nacos、Studio。

## 当前执行模型

```text
Run audit
  -> runtime config
  -> MCP route + Tool Guard
  -> ReactAgent
       -> PostgresSaver
       -> SummarizationHook
       -> ModelCallLimitHook
       -> ToolCallLimitHook
       -> TodoListInterceptor
       -> ToolErrorInterceptor
       -> filtered MCP tools
       -> rag_search
  -> summary / complete
  -> audit update
```

## 记忆边界

- PostgreSQL `GraphThread / GraphCheckpoint`：同一 session 的短期记忆和 checkpoint。
- `SummarizationHook`：近似 token 预算、摘要压缩、最近消息保留。
- MySQL `ai_agent_run / ai_agent_step_run`：审计，不是聊天消息。
- Run detail 旧上下文摘要字段暂时保留为空值，兼容已有前端。
- Store 长期记忆暂缓。

## RAG 边界

- 导入解析层改为 Alibaba `MarkdownDocumentParser`。
- Parent-Child、PGVector、BM25、RRF、Small-to-Big 和 evidence 去重继续保留。
- GraphRuntime 通过本地 `rag_search` 工具自主触发检索。
- PGVector 语义召回异常时 fail-open 到 Elasticsearch BM25，并保留告警。

## 代码健康观察项

- `AiAgentController` 仍有 DTO 手工映射；接口继续扩展时再抽 mapper。
- `ToolGuardPolicy` 仍按工具名启发式分级；真实工具规模扩大后改为配置化策略。
- 开发环境 `PostgresSaver` 使用 `CREATE_IF_NOT_EXISTS`；生产环境应预建表并调整为 `CREATE_NONE`。
- Graph 内部 tool/todo 细粒度事件目前只保留关键 SSE；需要更完整 trace 时再接事件适配器。
- `SummarizationHook` 是近似 token 估算；只有出现明确预算误差问题时再接精确 tokenizer。
- MCP 客户端生命周期已从 domain 下沉 infrastructure：装配阶段只登记配置，后台并发预热，请求命中路由时按需初始化并缓存复用。
- Chat 和 Embedding 已统一到百炼 OpenAI-compatible 接口，默认 Embedding 模型为 `text-embedding-v4`、维度为 `1024`。

## Phase 8 验收

已通过：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q "-DskipTests" compile
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=ReactAgentCompatibilityTest,RuntimeToolCapabilityServiceTest,GraphRagSearchToolCallbackTest,RagParentChildIngestionServiceTest,AgentRunLifecycleVOTest" test
mvn -q "-DskipTests=false" test
mvn -q "-DskipTests" package
git diff --check
```

验收结果：

- 离线目标测试：`13` 个通过。
- 完整回归：`124` 个通过。
- PowerShell smoke 脚本语法检查通过。
- MySQL 结构迁移通过：运行时配置 `1` 行，旧表 `0` 张，旧压缩字段 `0` 个。
- PostgreSQL checkpoint 表检查通过：`GraphThread / GraphCheckpoint` 共 `2` 张。
- 真实模型 smoke 通过：模型可自主调用 `write_todos`、`rag_search` 和筛选后的 MCP ToolCallback。
- `AI_AGENT_VECTOR_STORE_ENABLED=false` 时，`run-local-smoke.ps1` 通过：GraphRuntime、工具路由、BM25 RAG evidence 和同 session checkpoint 均完成验收。
- HTTP cancel smoke 通过：Run 与运行中的 Step 同步变为 `CANCELLED`。

## Phase 9 技术评估

- 不引入新的 Embedding SDK：百炼提供 OpenAI-compatible `/embeddings`，复用现有 `OpenAiEmbeddingModel` 即可。
- 不保留双 Embedding 路径：旧配置、脚本和文档全部删除，避免环境变量和索引叙事分叉。
- MCP 不再在装配链中串行 `initialize()`：SDK 传输与生命周期进入 infrastructure，使用后台并发预热、请求期按需初始化、成功结果缓存、失败后可重试和关闭回收。
- Windows stdio 按 Spring AI 官方建议使用 `cmd.exe /c npx`；npm 包固定版本，并使用本地缓存优先，避免 `@latest` 放大冷启动开销。
- MCP 请求与初始化超时统一使用秒，路由请求只等待有限时间；未及时就绪时本轮无工具回退，不阻塞 GraphRuntime。

## Phase 9 验收

已通过：

```powershell
.\scripts\dev\import-markdown-rag.ps1
.\scripts\dev\run-local-smoke.ps1
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=RagParentChildIngestionServiceTest,McpClientLifecycleManagerTest,GraphRagSearchToolCallbackTest" test
mvn -q "-DskipTests=false" test
mvn -q "-DskipTests" package
git diff --check
```

验收结果：

- 百炼 `text-embedding-v4` 真实调用通过，Embedding 维度为 `1024`。
- Markdown 重导入通过：MySQL 为 `2` 个文档、`6` 个父块、`6` 个子块；PGVector 与 Elasticsearch 均为 `6` 条子块索引。
- 中文 Markdown 入库字节已恢复为正确 UTF-8；增加中文内容保持性单测，防止 Windows JDK 默认字符集导致回归。
- 应用约 `11` 秒完成启动；MCP 配置登记发生在应用就绪后，客户端后台并发预热，不再阻塞 Spring Boot readiness。
- live smoke 中 `rag_search` 同时命中 `vectorHits=6` 与 `bm25Hits=6`，GraphRuntime、MCP 工具路由、RAG evidence 和 PostgreSQL session checkpoint 均通过。
- RAG 导入脚本关闭 Agent 自动装配，不再启动无关 MCP 预热。
- Maven 测试环境默认关闭 MCP 后台预热，避免单测依赖外部 npm 进程；live smoke 继续覆盖真实预热。
- 完整 Maven 回归：`126` 个测试通过，`0` 失败。
