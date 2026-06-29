# Spring AI MCP Client 与受控工具证据

## 常见接入方式

Spring AI MCP Client 常见的接入方式包括 Stdio、SSE 和 Streamable HTTP。Stdio 适合本地进程型工具，SSE 属于较早的事件流协议，Streamable HTTP 更适合服务化工具和统一治理。

AI Agent Station 重点适配 Stdio 与 Streamable HTTP，默认只保留 Context7 文档查询和 Exa 网络搜索两个只读 MCP Server，用于 Java 项目知识问答和技术资料调研。

## Advisor Chain 中的工具治理

MCP 不再由自定义关键词打分路由决定注入范围。当前链路以 Spring AI `ChatClient` 和 Advisor Chain 为主入口，项目只注册企业知识助手需要的只读 MCP Server，并通过受保护的 ToolCallback 包装层控制调用边界。

- `PROJECT_KNOWLEDGE` 进入携带 `ragId` 范围的本地 PGVector。
- `OFFICIAL_DOCS` 使用 Context7 等文档类 MCP。
- `WEB_RESEARCH` 使用 Exa 等搜索类 MCP。

模型不能指定危险工具，也不能使用 create、update、write、send、notify、memory 或 shell 类工具。工具注册边界、风险分级、结果限长、参数脱敏和结构化错误响应共同组成工具安全基线。

## 真实工具证据

外部检索通过 Spring AI Tool Calling 执行。系统记录真实工具名、脱敏参数、成功状态和受限长度结果，再由 Observation Trace 层把结果规范化为带来源、URI、工具名和检索时间的 Evidence。

模型输出中的普通文本或伪造的 `<tool_call>` 不能直接成为证据。没有可归属 URI 的结果只能作为低可信补充，不能独立满足证据充分条件。

## Spring AI 2 主链路

当前主链路不再维护 Harness Action Loop，也不要求模型输出 `RETRIEVE`、`ASK_CLARIFY` 或 `FINALIZE` 动作 JSON。上下文预算、Session 短期记忆、RAG evidence、工具调用和 trace 都收敛到固定顺序的 Advisor Chain 中，项目保留证据治理、拒答边界和工具安全包装。
