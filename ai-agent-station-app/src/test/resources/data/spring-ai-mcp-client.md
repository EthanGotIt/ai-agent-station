# Spring AI MCP Client 与受控工具证据

## 常见接入方式

Spring AI MCP Client 常见的接入方式包括 Stdio、SSE 和 Streamable HTTP。Stdio 适合本地进程型工具，SSE 属于较早的事件流协议，Streamable HTTP 更适合服务化工具和统一治理。

AI Agent Station 重点适配 Stdio 与 Streamable HTTP，默认只保留 Context7 文档查询和 Exa 网络搜索两个只读 MCP Server，用于 Java 项目知识问答和技术资料调研。

## 按证据来源路由

MCP 不在请求开始时全量注入。Controlled Agent Harness 先让模型在 `PROJECT_KNOWLEDGE`、`OFFICIAL_DOCS` 和 `WEB_RESEARCH` 三种高层来源中做选择。

- `PROJECT_KNOWLEDGE` 进入携带 `ragId` 范围的本地 PGVector。
- `OFFICIAL_DOCS` 只选择文档类 MCP。
- `WEB_RESEARCH` 只选择搜索类 MCP。

后端 Policy 再校验最大轮次、重复 query、外部检索次数和只读工具范围。模型不能指定具体 MCP Server，也不能使用 create、update、write、send、notify、memory 或 shell 类工具。

## 真实工具证据

外部检索通过 Spring AI `toolContext` 向受保护的 ToolCallback 传入本轮调用记录器。系统记录真实工具名、脱敏参数、成功状态和受限长度结果，再把结果规范化为带来源、URI、工具名和检索时间的 Evidence。

模型输出中的普通文本或伪造的 `<tool_call>` 不能直接成为证据。没有可归属 URI 的结果只能作为低可信补充，不能独立满足证据充分条件。

## Controlled Agent Harness

模型每轮只能输出 `RETRIEVE`、`ASK_CLARIFY` 或 `FINALIZE`。检索只更新 Evidence Board，不直接生成答案。下一轮模型评估充分性，确定性 Evidence Policy 负责复核，最终回答由后端基于 Evidence Board 生成并校验 `[E1]` 等引用。

最大决策轮次为四轮，最多两次 evidence retrieval 和一次外部检索，重复来源加 query 会被拒绝，因此不会形成无限 ReAct 循环。
