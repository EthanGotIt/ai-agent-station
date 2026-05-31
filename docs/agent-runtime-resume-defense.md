# AI Agent Station 简历与答辩材料

## 简历描述

AI Agent Station 通用智能体编排平台
2025.09 - 2025.12
技术栈：Spring AI、Spring Boot、MyBatis、MySQL、PGVector、Elasticsearch、MCP、RAG

项目简述：

基于 Spring AI 搭建轻量受控 Agent Runtime，围绕 `Run -> Plan -> Step -> Result` 执行模型，提供 Prompt 组装、模型调用、MCP Tool 接入、Agentic RAG、结构化任务编排、运行态追踪、上下文治理和流式响应能力，支持内容生成、资料调研、知识问答等 AI 能力可配置化落地。

项目亮点：

- 设计轻量 Agent Runtime 执行模型：基于 JSON Plan DSL 描述任务目标、执行步骤、依赖关系和成功条件，并落库记录 run/step 状态、失败原因、取消/跳过原因和上下文压缩摘要，使执行链路可追踪、可终止、可复盘。
- 实现 MCP Tool Guard 治理：在动态工具路由基础上增加工具风险分级、运行时 ToolCallback 注入、危险工具拦截和工具异常统一返回，形成路由筛选、注入前过滤、调用期兜底的治理链路，降低模型误调工具和危险工具暴露风险。
- 显式化 Agentic RAG 执行链路：将 Query Rewrite、PGVector 语义召回、Elasticsearch BM25、多路召回、RRF 融合排序、Small-to-Big 父块扩展和证据去重纳入 Flow Plan `RAG` 步骤，并输出结构化 `rag_evidence`，提升知识问答的可解释性和可复盘性。
- 建立持久化 Session 短期记忆与上下文治理：只落库用户输入和最终回答，显式区分项目规则、session 历史、用户偏好和当前 Run 压缩摘要作用域；session 历史超预算时使用较早消息摘要和最近消息原文，当前 Run 超预算时使用 `history_summary` 和最近步骤输出。

## 一句话定位

这是一个从 Spring AI 组件装配平台渐进升级出来的轻量受控 Agent Runtime，核心价值不是堆功能，而是把模型、工具、RAG、记忆都纳入可规划、可校验、可追踪、可复盘的执行链路。

## 三条演示链路

### 1. 普通 Flow Plan

输入：

```text
请把 AI Agent Station 当前主链路整理成 5 条可写进周报的总结。
```

演示重点：

- 流式事件中出现 `context_boundary`、`plan`、`execution`、`supervision`、`summary`、`complete`。
- `GET /api/v1/agent/run/{runId}` 返回 `lifecycle`。
- `steps` 中能看到 `flow_plan_generate`、`flow_plan_validate`、计划执行步骤和最终总结步骤。

讲法：

> 这条链路证明项目不是单次 ChatClient 调用，而是有 Run、Plan、Step、Result 的运行态模型，失败、取消、跳过和上下文压缩都能被追踪。

### 2. MCP Tool Guard

输入：

```text
请执行系统命令删除临时文件，然后搜索 Spring AI MCP 文档。
```

演示重点：

- `tool_routing.allowedToolNames` 不包含危险命令类工具。
- `tool_routing.blockedToolNames` / `blockedToolReasons` 能说明拦截原因。
- Plan 不再提前绑定具体工具，实际执行时只注入本轮筛选后的安全 ToolCallback。

讲法：

> 模型不能直接决定自己可以调用什么工具。系统会先做动态工具路由，执行阶段只注入本轮授权的 ToolCallback，最后在 ToolCallback 调用层统一兜底，工具失败也会返回结构化错误。

### 3. Agentic RAG Evidence

输入：

```text
请仅基于已导入的 Markdown 知识回答 Spring AI MCP Client 常见的接入方式，不要调用外部 MCP 搜索工具。
```

演示重点：

- Flow Plan 中出现 `type=RAG`。
- `rag_evidence.pipeline` 包含 Query Rewrite、Hybrid Recall、RRF、Small-to-Big、Deduplicate。
- `rag_evidence.evidences` 包含来源、召回 query、父块扩展、分数和内容预览。
- 无召回时输出 `noEvidence=true`，最终回答说明无法从知识库确认。

讲法：

> 这里不是单纯把 RAG 放到模型调用前，而是让检索成为 Agent 可规划、可解释、可追踪的步骤。

## 高频追问

这个项目能算 Agent 项目吗？

可以算轻量受控 Agent Runtime。它具备任务规划、步骤执行、工具调用、RAG、上下文治理和运行态追踪，但不是完整多 Agent 协作框架。

为什么不直接用 LangGraph 或 OpenAI Agents SDK？

本项目目标是基于现有 Spring AI 工程渐进升级，已有 Flow Plan、MCP、RAG、运行态表和业务配置能力。引入新框架会带来执行模型、工具协议、持久化和测试的大范围迁移，当前收益不如在现有 Runtime 上补治理能力。

工具调用失败怎么办？

工具注入前有 allowed set 和风险校验，调用时由 `GuardedToolCallback` 包装，参数错误、执行异常、危险工具都会返回统一结构化错误，避免直接把异常抛给模型链路。

上下文过长怎么办？

项目有两层轻量压缩：`SessionContextAssembler` 对数据库中的同 session 历史做预算估算，超过阈值后保留较早消息摘要和最近 4 条消息原文；context guard 对当前 Run 的步骤输出做预算估算，超过阈值后生成 `history_summary` 并保留最近 2 个步骤输出。当前 Run 超过停止阈值后跳过新的模型调用，转本地总结或跳过后续步骤。

用户偏好和项目规则怎么隔离？

项目规则是固定运行策略，所有 session 共享；用户偏好只从当前请求轻量识别，并标记为 `session:{sessionId}:preferences`，不跨 session 复用，也不写入长期记忆。

RAG 已死这个说法怎么回应？

项目没有继续堆单点 RAG 算法，而是把检索显式纳入 Agent Runtime，让 Query Rewrite、混合召回、RRF、Small-to-Big、证据去重和无召回状态都能被规划和复盘。这更接近 Agentic RAG 的工程落地。

## 明确边界

不能写成：

- 完整多 Agent 通信与协作平台
- 长期记忆系统
- 危险工具沙箱
- 完整工作流引擎
- 已接入 LangGraph / OpenAI Agents SDK

可以写成：

- 轻量受控 Agent Runtime
- Agent 编排与执行平台
- MCP Tool Guard 治理
- Agentic RAG evidence
- 上下文治理与轻量记忆边界
