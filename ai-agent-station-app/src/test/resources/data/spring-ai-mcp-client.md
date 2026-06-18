# Spring AI MCP Client 使用指南

## 常见接入方式

Spring AI MCP Client 常见的接入方式包括 Stdio、SSE 和 Streamable HTTP。  
其中 Stdio 更适合本地进程型工具，接入链路短，调试也比较直接；SSE 更偏早期事件流式协议；Streamable HTTP 更适合服务化工具，便于做统一鉴权、监控和限流治理。

在 AI Agent Station 里，我们重点落地的是 Stdio 和 Streamable HTTP。当前默认保留文档和搜索类只读工具，服务企业知识助手和技术资料调研场景。平台会缓存可用工具元数据，运行时再按问题动态筛选本轮工具集合。

## 运行时工具路由

MCP 工具不会默认全量暴露给模型。平台会先基于当前智能体配置收集 MCP Server 的工具清单，再根据本轮用户问题和工具标签做运行时路由，最后收敛成 tool_routing 和 allowedToolNames。  
这样做的目的是减少工具噪声，避免模型在简单任务里误用外部工具，也方便把高风险工具限制在本轮授权范围内。

举例来说，如果用户是在做资料调研，系统会优先保留搜索和文档类工具；如果只是普通总结或润色，就会让模型直接回答，不额外开放外部工具。
这种模式让 Agent 的执行更接近“按轮授权”，而不是一次性把所有能力都交给模型自由发挥。

## Controlled Agent Harness 配合方式

在 Controlled Agent Harness 模式下，模型每轮只能输出一个受控 action。
系统先根据用户目标和已有 observation 判断是否需要 `RAG_RETRIEVE`、`MCP_READ`、`LLM_RESPOND` 或 `FINAL`，再由策略层校验 action 类型、最大轮次和只读工具边界。这样可以保留 Agent 的动态决策能力，同时避免模型无限循环或随意调用写入工具。

当某个 action 需要工具时，平台会把工具调用结果、证据内容和运行态摘要转成 observation，再交给下一轮 action 决策。
这个链路的好处是过程可追踪，出了问题也容易定位到底是 action 决策、工具调用、证据质量，还是模型最终回答的问题。
