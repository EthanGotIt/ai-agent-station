# AI Agent Station 面试 Defense

## 项目定位

一句话回答：

> 这是一个面向企业 Java 项目知识和技术资料调研的轻量受控 Agent Runtime。它解决的不是“再做一个网页聊天 AI”，而是在内部知识、版本化官方文档和外部资料之间受控选择证据，输出可引用、可拒答、可复盘的回答。

项目不是多 Agent 平台、长期记忆系统、危险工具沙箱或通用工作流引擎。

## 为什么项目有业务意义

> 通用网页 AI 能搜索公开网页，但不知道企业内部项目知识，也不能保证回答引用的是当前项目允许的知识库和官方版本文档。我的项目把问题拆成三个来源：项目知识、官方文档和外部调研，由 Harness 决定查哪个来源，后端再验证 evidence 是否可归因，最后强制使用 Evidence ID 引用。价值在证据治理和可控执行，不在“也能搜索网页”。

## 为什么不是固定 Workflow

> 旧版 Flow Plan 先生成完整步骤，再逐步执行，稳定但很像 workflow。现在模型每轮只选择 `RETRIEVE`、`ASK_CLARIFY`、`FINALIZE` 三个高层动作之一，下一步取决于 Evidence Board 的真实结果。底层 Policy 仍限制四轮决策、两次检索、一次外部检索和重复 query，所以它有动态决策，但不是无限 ReAct 循环。

代码路径：`AgentHarnessExecuteService -> AgentActionParser -> AgentActionPolicy -> HarnessActionExecutor`。

## 为什么只保留三个 Action

> `RAG_PLAN` 和 `EVALUATE_EVIDENCE` 原来没有独立副作用，只会增加模型调用和状态数量；独立 `MCP_READ` 又和 RAG 的外部 evidence 重复。我把它们收敛成统一 `RETRIEVE(sourceType, queries)`。模型只选高层来源，不能控制 PGVector、BM25、RRF、父块扩展或具体 MCP 工具。

## FINALIZE 为什么不能携带答案

> 如果让模型在决策 JSON 里直接给答案，Evidence Policy 就可能被绕过。现在 FINALIZE 只表达“希望收口”，真正答案由 `GroundedAnswerService` 根据 Evidence Board 生成。事实回答必须引用 `[E1]` 等存在的 Evidence ID，引用校验失败最多纠正一次，再失败就拒答。

## RAG 为什么称为 Agentic

> 它不再固定执行 Query Rewrite、双路召回、RRF、父块扩展全套链路。Harness 根据现有 evidence 决定来源和是否需要第二次检索，PGVector 是默认通道，只有精确术语或语义无结果才补 BM25，两个通道都命中才做 RRF，只有 `CONTEXT_INCOMPLETE` 才扩父块。重点是按需检索和证据闭环，不是算法堆叠。

对外称“受控 Agentic RAG 证据闭环”，不把 Agentic RAG 3.0 说成行业标准。

## Evidence Board 保存什么

> Evidence Board 只存在于一次 Run，保存已执行的来源/query、规范化证据、检索轮次、模型 assessment、缺失信息和重复检索键。它负责去重和给下一轮 Harness 提供压缩 observation，不进入 Session 记忆，避免把外部事实长期污染用户上下文。

## MCP evidence 为什么可信

> 外部检索时，系统通过 Spring AI `toolContext` 给 `GuardedToolCallback` 传入调用记录器，记录真实工具名、脱敏参数、成功状态和受限原始结果。`McpEvidenceNormalizer` 从真实 ToolCallback 结果提取 title、URI 和 content，而不是把模型整理后的回答当证据。没有 URI 的文本只能作为低可信补充，不能独立证明“最新版本”这类事实。

## MCP 为什么不在开始时全量注入

> 项目知识问题根本不需要 MCP。只有 Harness 选择 `OFFICIAL_DOCS` 或 `WEB_RESEARCH` 时，系统才分别路由 Context7 或 Exa，然后做只读过滤和 allowed set 校验。这样减少工具描述噪声，也避免无关 MCP 初始化拖慢每个请求。

## 怎么防止危险工具

> 第一层按 evidence source 只选 docs/search MCP，第二层只允许 search、docs、fetch、read、get、open、list、resolve 语义的工具，第三层 `GuardedToolCallback` 在调用期再次检查授权集合和危险名称。create、update、write、send、notify、memory、shell 不会进入证据链路。

## 工具调用失败怎么办

> 参数错误、未授权和调用异常会被结构化归一化，并记录为失败 ToolInvocation。只有成功调用才会进入 evidence normalizer。工具失败后 Harness 可以换来源、改写 query 或基于已有 evidence 收口，不能把错误文本当成检索结果。

## 为什么 PGVector 而不是更重的向量数据库

> 这是根据当前数据量、部署目标和运维成本做的选择。PGVector 已能满足项目知识语义检索，并和 PostgreSQL 一起完成轻量部署。只有进入更大规模、多租户隔离、高并发或专门索引治理场景时，才有充分理由引入独立向量数据库。这是技术选型方法，不是说 Milvus 本身不好。

## BM25、RRF、Small-to-Big 会不会过度设计

> 现在它们不是默认全开。项目冻结了 60 条评测数据，BM25/RRF 只有在精确术语子集 Hit@5 提升至少 10 个百分点或修复至少 3 个 case 时保留；Small-to-Big 也有关键点覆盖率和 Faithfulness 门槛。live evaluation 未完成前，我不会在简历中写确定的效果提升。

## 记忆机制是什么

> 这是 Session 短期记忆。消息表只保存用户输入和最终回答原文，只有 USER 和 ASSISTANT 都存在的成功完整 Turn 才会再次注入。Session 表保存结构化摘要、已总结游标、乐观锁版本和 30 天过期时间，Prompt 使用摘要加最近四个完整 Turn。

## 为什么不直接使用 Spring AI ChatMemory

> 我借鉴了 MessageWindowChatMemory 的完整消息窗口语义，但项目还需要按 Run 状态排除失败输入、保存结构化摘要游标和做乐观锁更新。为了框架对齐再包一层 ChatMemory 只会增加适配代码，所以保留了更符合当前 Run 模型的自定义仓储。

## 摘要会不会把错误事实记住

> 摘要只提取用户明确表达的目标、约束、确认决策、未解决问题和回答偏好，不保存工具输出、外部事实或模型猜测。Evidence Board 生命周期只在当前 Run，和 Session Memory 分离。

## 上下文过长怎么办

> Session 层按完整 Turn 淘汰，不截断单条消息。模型调用层用 `PromptBudgetAssembler` 按当前问题、项目规则、evidence、Session 上下文、observation 的顺序组装。估算单位叫 context-units，是中英文 heuristic，不是精确 tokenizer，也不再把多次模型调用字符数累计成一个假窗口。

## 并发更新记忆怎么办

> Session 表有 version 乐观锁，冲突后重新读取并重试一次。摘要失败不会影响主 Run，消息原文仍保留；成功交互会续期 30 天，每日任务清理过期 Session 和消息。

## 项目当前最诚实的边界

- 已完成：三动作 Controlled Harness、按来源 evidence retrieval、ragId scope、真实 MCP evidence、引用校验、完整 Turn 记忆、默认测试隔离、60 条评测数据。
- 已验收：187 个默认测试、108 个 integration 测试、Docker 数据组件、Harness live、本地 RAG 与 MCP ToolCallback 注入。
- 待外部额度恢复后验收：完整 MCP evidence 回答、三组 RAG 对照和消融结论；这些结果完成前不写效果提升数字。
- 暂不做：多 Agent、长期向量记忆、精确 tokenizer、reranker、权限系统、前端控制台和新 Agent SDK。
