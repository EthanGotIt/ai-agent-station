# Phase 9：百炼 Embedding 与 MCP 生命周期收敛

## 状态

已完成。Embedding 已统一为百炼 `text-embedding-v4`，旧 Embedding 配置已删除；MCP Client 生命周期已从领域装配链下沉到 infrastructure。

## 技术评估

- 百炼提供 OpenAI-compatible `/embeddings`，继续使用 Spring AI `OpenAiEmbeddingModel`，不增加另一套 SDK。
- MCP 初始化属于基础设施生命周期，不应由 domain 装配节点同步承担。
- Windows stdio 使用 `cmd.exe /c npx`，固定 npm 包版本，并启用本地缓存优先。
- 请求期只有限等待 MCP 初始化；超时则本轮无外部工具回退，GraphRuntime 继续执行。

## 代码变化

- 默认 Embedding 改为百炼 `text-embedding-v4`、`1024` 维，Chat 与 Embedding 复用 `OPENAI_API_KEY`。
- 删除旧 Embedding 环境变量、脚本和文档遗留，不保留双配置路径。
- 新增 `IMcpClientLifecyclePort` 和 infrastructure `McpClientLifecycleManager`。
- domain `AiClientToolMcpNode` 只登记配置，不再持有 SDK 传输、动态 MCP Bean 和初始化逻辑。
- MCP Client 支持后台并发预热、路由命中时懒初始化、成功缓存、失败重试、请求期有限等待和停机回收。
- MCP SQL seed 固定 npm 包版本，超时单位统一为秒。
- Alibaba `MarkdownDocumentParser` 内部使用默认 charset 的 `InputStreamReader`；导入服务增加无损字符集适配和中文回归测试。
- RAG 导入脚本关闭 Agent 自动装配，不再启动与导入无关的 MCP 预热。
- Maven 测试环境默认关闭 MCP 后台预热，避免单测依赖外部 npm 进程；live smoke 继续覆盖真实预热。

## 架构边界

```text
domain AiClientToolMcpNode
  -> register MCP configuration only
infrastructure McpClientLifecycleManager
  -> concurrent prewarm
  -> lazy initialize on routed request
  -> cache / retry / timeout fallback / close
GraphRuntime
  -> receive filtered ToolCallback only
```

## 验收记录

- 百炼 Embedding 探测通过：`text-embedding-v4` 返回 `1024` 维向量。
- Markdown 重导入通过：MySQL `2 / 6 / 6`，PGVector `6`，Elasticsearch `6`。
- live `rag_search`：`vectorHits=6`、`bm25Hits=6`。
- live smoke：GraphRuntime、MCP 路由、RAG evidence、PostgreSQL session checkpoint 全部通过。
- 应用约 `11` 秒完成启动；MCP 后台预热不阻塞 readiness；停机后无 MCP 子进程残留。
- 完整 Maven 回归：`126` 个测试通过，`0` 失败。

## 保留边界

- 当前仍是单 Agent GraphRuntime。
- PGVector 异常时继续 fail-open 到 BM25。
- Store 长期记忆、多 Agent handoff、HITL 和真实危险工具沙箱暂缓。
