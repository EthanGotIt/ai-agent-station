# AI Agent Station 答辩口径

## 一句话定位

项目已经从自研 JSON Flow Plan 编排升级为基于 Spring AI Alibaba `ReactAgent` 的轻量受控 Agent Runtime：执行图、session checkpoint 和上下文摘要使用官方能力，MCP Tool Guard 与 Hybrid RAG 保留项目自定义治理。

## 为什么替换 FlowRuntime

旧 FlowRuntime 能展示编排思路，但继续扩展 checkpoint、重入、Todo 和上下文摘要会增加自研维护成本。Phase 8 直接替换执行内核，不保留双路径；历史实现仍可从 Git 查看。

## 记忆怎么做

- 相同 `sessionId` 映射为稳定 Graph threadId。
- 官方 `PostgresSaver` 写入 `GraphThread / GraphCheckpoint`。
- 官方 `SummarizationHook` 在上下文过长时压缩摘要，并保留最近消息。
- MySQL Run 表只做审计，不再重复保存每条消息。
- 当前不是跨 session 长期画像；Store 留到真实需求出现后再做。

## 工具怎么治理

- 请求期按用户输入动态路由 MCP 工具。
- 注入前按 allowed set 和风险规则再次过滤。
- 调用期使用 `GuardedToolCallback` 处理越权、危险调用、参数错误和工具异常。
- 模型只看到本轮注入的工具，不注入全量 MCP 表。

## RAG 怎么做

- `rag_search` 是 GraphRuntime 可自主调用的本地工具。
- 导入侧使用 Alibaba `MarkdownDocumentParser`，继续做 Parent-Child 分块。
- 检索侧保留 Query Rewrite、PGVector + BM25、RRF、Small-to-Big 和 evidence 去重。
- `rag_evidence` 事件用于解释召回来源和父块扩展。

## 为什么没有直接做多 Agent

当前业务还没有明确的 handoff 和并行协作边界。先把单 Agent 的 checkpoint、上下文、工具治理和 evidence 做稳，能避免为了展示概念引入额外复杂度。

## 边界说明

- `SummarizationHook` 使用近似 token 估算，不是精确 Qwen tokenizer。
- 当前不包含长期 Store、HITL、真实危险工具沙箱、Nacos 和 Studio。
