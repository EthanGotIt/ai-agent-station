# AI Agent Station 简历与答辩材料

## 简历描述

AI Agent Station 通用智能体平台
2025.09 - 2025.12
技术栈：Spring AI、Spring Boot、MyBatis、MySQL、PGVector、Elasticsearch、MCP、RAG

项目简述：

基于 Spring AI 搭建面向企业知识助手和技术资料调研的通用智能体平台，提供 Prompt 组装、模型调用、MCP Tool 接入、Agentic RAG、受控 Action Loop、运行态追踪、上下文治理和流式响应能力，支持资料调研、知识问答、内容生成等 AI 能力可配置化落地。

项目亮点：

- 设计 Controlled Agent Harness 执行内核：将请求生命周期抽象为 `Run -> HarnessContext -> Action -> Observation -> Evaluation -> Final`，用受控 Action Loop 替代固定工作流节点，并通过最大轮次、上下文预算、取消和终止策略限制模型无限循环。
- 实现 MCP 只读工具治理：按企业知识助手场景动态筛选 docs/search 类工具，并在 RAG 子链路中只允许 `search / docs / fetch / read / get / open` 等只读 evidence 工具，避免写入、通知、记忆和命令类工具暴露给模型。
- 收敛 Agentic RAG 3.0 链路：以 `AgenticRagRuntime` 作为 RAG 唯一主入口，支持检索规划、PGVector/BM25 本地召回、证据评估、最多一次二次检索、MCP 只读 evidence 融合和真实 `rag_evidence` trace。
- 建立持久化 Session 短期记忆与上下文治理：只落库用户输入和最终回答，显式区分项目规则、session 历史、用户偏好和当前 Run 压缩摘要作用域，避免内部 prompt 污染用户记忆。

## 一句话定位

这是一个基于 Spring AI 的轻量受控 Agent Runtime。项目价值不是模拟一个大而全的多 Agent 框架，而是围绕“企业知识助手 / 技术资料调研 / 知识库问答”场景，把模型、工具、RAG 和记忆放进可控、可追踪、可复盘的执行链路。

## 三条演示链路

### 1. Controlled Agent Harness

输入：

```text
请把 AI Agent Station 当前主链路整理成 5 条可写进周报的总结。
```

演示重点：

- 流式事件中出现 `context_boundary`、`tool_routing`、`harness_observation`、`summary`、`complete`。
- `GET /api/v1/agent/run/{runId}` 返回 `lifecycle` 和 `contextBoundary`。
- `steps` 中能看到 `harness_root`、`harness_tool_routing`、`harness_action_*`。

面试回答稿：

> 我没有把它继续做成固定流程，因为固定节点会让项目看起来像流程编排工具。现在主入口是 Controlled Agent Harness，模型每轮只能输出一个受控 action，比如 `RAG_RETRIEVE`、`MCP_READ`、`LLM_RESPOND` 或 `FINAL`。系统会校验 action 类型、最大轮次、上下文预算和取消状态，所以它具备 Agent 的动态决策能力，但又不是完全放任模型自由循环。

### 2. MCP 只读工具治理

输入：

```text
请执行系统命令删除临时文件，然后搜索 Spring AI MCP 文档。
```

演示重点：

- `tool_routing.allowedToolNames` 只包含 docs/search 类工具。
- `blockedToolNames / blockedToolReasons` 能说明危险工具或写入工具为什么不能进 RAG evidence 子链路。
- 默认 seed 只保留 `context7-docs` 和 `exa-search`。

面试回答稿：

> 我把 MCP 的定位收敛成资料调研和 evidence 补充，不再默认接通知、记忆、顺序推理这类和场景关系不强的工具。模型不能自己决定可以调用什么工具，系统先按场景做路由，再按工具名做只读校验，最后 ToolCallback 调用期还有兜底。这样能解释为什么工具治理是生产级 Agent 必须考虑的点。

### 3. Agentic RAG 3.0 Trace

输入：

```text
请仅基于已导入的 Markdown 知识回答 Spring AI MCP Client 常见的接入方式，不要编造证据外内容。
```

演示重点：

- Action 决策进入 `RAG_RETRIEVE`。
- `rag_evidence` 不再是固定 pipeline 文案，而是 `AgenticRagTraceVO` 的真实执行轨迹。
- trace 包含 intent、plannedQueries、retrievalRounds、是否触发二次检索、finalEvidences 和 noEvidenceReason。

面试回答稿：

> 旧版更接近 Advanced RAG，是把 Query Rewrite、PGVector、BM25、RRF、Small-to-Big 串起来。新版我没有继续堆算法，而是把 RAG 收敛成 `AgenticRagRuntime`，先判断意图和改写 query，再检索本地知识，证据不足时最多二次检索，必要时融合 MCP 只读资料，最后基于证据回答。重点是检索决策闭环和证据评估闭环，而不是把所有 RAG 技术每次都强制跑一遍。

## 高频追问

这个项目能算 Agent 项目吗？

可以算轻量受控 Agent Runtime。它不是单次 ChatClient 调用，也不是固定流程；它有 action 决策、工具治理、RAG 子链路、上下文预算、取消终止和运行态复盘。但它不是完整多 Agent 协作系统，也没有长期记忆画像和危险工具沙箱。

为什么不用固定计划式工作流？

旧版计划式执行更像工作流：先让模型生成计划，再按节点执行，优点是稳定，缺点是过于固定，面试时容易被问成“这是不是只是固定流程”。Harness 的思路是把模型输出收敛成少量 action，让系统控制边界，让模型负责选择下一步，这更接近 Agent Runtime。

为什么不直接用 LangGraph、OpenHarness 或 OpenAI Agents SDK？

这个项目基于 Spring AI、MyBatis、MySQL、PGVector 和现有运行态表已经形成了工程闭环，引入新框架会带来执行模型、工具协议、持久化和测试的大范围迁移。当前选择是借鉴 harness 思想，不引入依赖，先把执行边界、工具治理和 RAG trace 做扎实。

工具调用失败怎么办？

工具路由阶段只选 docs/search 类工具，RAG 子链路再过滤只读工具，调用期由 ToolCallback 包装做异常兜底。参数错误、工具不可用或执行异常不会被模型当成真实结果使用，最终回答要说明失败或证据不足。

上下文过长怎么办？

有两层压缩。session 历史从数据库加载时会按预算保留摘要和最近消息；当前 Run 的 action observation 超预算后会生成 `history_summary` 并保留最近两个输出。当前只是轻量估算，不宣称精确 tokenizer。

RAG 为什么不继续堆 PGVector、BM25、RRF、Small-to-Big？

这些能力仍可用，但不再作为简历主亮点堆叠。真正更像 Agentic RAG 的地方是系统会决定何时检索、证据是否足够、是否需要二次检索、是否需要 MCP 只读 evidence 补充，以及最终回答是否能被证据支撑。

为什么用 PGVector 而不是更重的向量数据库？

这个项目的数据规模和演示目标更适合轻量部署闭环。PGVector 能和 PostgreSQL 一起提供语义召回能力，减少额外组件和运维成本；如果后续进入更大规模文档、多租户隔离或高并发向量检索，再评估专门向量数据库。

## 明确边界

不能写成：

- 完整多 Agent 通信与协作平台
- 长期记忆系统
- 危险工具沙箱
- 完整工作流引擎
- 已接入 LangGraph / OpenHarness / OpenAI Agents SDK

可以写成：

- Controlled Agent Harness
- 轻量受控 Agent Runtime
- MCP 只读工具治理
- Agentic RAG 证据评估闭环
- 上下文治理与 session 短期记忆
