# AI Agent Station 面试 Defense

## 项目定位

一句话回答：

> 这是一个面向企业 Java 项目知识和技术资料调研的 Spring AI 2 智能体执行平台。它解决的不是“再做一个网页聊天 AI”，而是基于 ChatClient、Advisor Chain、MCP Tool Calling 和 evidence trace，把内部知识、官方文档和外部资料组织成可引用、可拒答、可复盘的回答。

项目不是多 Agent 平台、长期记忆系统、危险工具沙箱或通用工作流引擎。

## 为什么项目有业务意义

> 通用网页 AI 能搜索公开网页，但不知道企业内部项目知识，也不能保证回答引用的是当前项目允许的知识库和官方版本文档。我的项目通过 Spring AI 2 Advisor Chain 把项目知识检索、MCP 只读工具、Session 记忆和运行态 trace 放到同一条调用链里，后端再验证 evidence 是否可归因。价值在证据治理和工程可控，不在“也能搜索网页”。

## 为什么不是固定 Workflow

> 旧版 Flow Plan 先生成完整步骤，再逐步执行，稳定但很像 workflow。现在主链路收敛到 Spring AI 2 的 ChatClient 和 Advisor Chain，模型调用、上下文、记忆、RAG evidence、工具调用和 trace 都在同一条框架链路里。项目不再维护一套自研 Action Loop，只保留证据治理、引用校验和拒答这些业务控制点。

代码路径：`AgentDispatchService -> SpringAiAgentRuntime -> SpringAiChatClientPort -> ContextBudgetAdvisor / SessionMemoryAdvisor / EvidenceRetrievalAdvisor / ObservationTraceAdvisor`。

## 为什么删掉 Harness Action Loop

> Harness Action Loop 能体现 Agent 控制，但它和 Spring AI 2 的 Advisor Chain、Tool Calling 会形成两套编排。项目如果同时保留两套主路径，面试时很难讲清楚谁负责工具、谁负责记忆、谁负责 RAG。我现在把模型调用统一交给 ChatClient，把上下文、Session、RAG 和 trace 放到 Advisor Chain，项目只保留证据治理和安全边界。

## Advisor Chain 分层怎么讲

> 第一层 `ContextBudgetAdvisor` 做上下文预算守卫，第二层 `SessionMemoryAdvisor` 注入 session 记忆，第三层 `EvidenceRetrievalAdvisor` 查项目知识，工具调用交给 Spring AI Tool Calling，最后 `ObservationTraceAdvisor` 统一收集 evidence 和工具调用 trace。这样每层职责比较单一，也更符合 Spring AI 2 的主流用法。

## RAG 现在怎么定位

> 当前更适合讲“Evidence-Governed RAG”，而不是夸张说完整 Agentic RAG。项目知识检索由 `EvidenceRetrievalAdvisor` 接入，按 `ragId` 限定知识范围，返回 evidence 后由 `ObservationTraceAdvisor` 归一化为 trace。外部资料通过只读 MCP Tool Calling 补充，最终强调 evidence 可追踪、可引用和证据不足时不编造。

## 为什么不继续堆 Advanced RAG

> Advanced RAG 的 Query Rewrite、BM25、RRF、父块扩展都有价值，但不是每次都该全开。项目现在把这些能力放在本地 evidence 检索能力里按需使用，简历不再把算法堆叠作为主亮点，而是讲清楚知识范围、证据来源、工具调用和回答 trace。

## evidence trace 保存什么

> evidence trace 是一次 Run 内的观测结果，包含最终 evidence、来源类型、工具名、URI、内容摘要和证据充分性。它不会进入 Session 记忆，避免把外部事实长期写进用户上下文。Session 只记用户偏好、约束和成功完整对话，不保存工具原始输出。

## MCP evidence 为什么可信

> 外部检索时，系统通过 Spring AI `toolContext` 给 `GuardedToolCallback` 传入调用记录器，记录真实工具名、脱敏参数、成功状态和受限原始结果。`McpEvidenceNormalizer` 从真实 ToolCallback 结果提取 title、URI 和 content，而不是把模型整理后的回答当证据。没有 URI 的文本只能作为低可信补充，不能独立证明“最新版本”这类事实。

## MCP 为什么不在开始时全量注入

> 项目知识问题主要走本地 RAG，不需要把所有 MCP 都塞进模型上下文。现在不再做自定义关键词打分路由，而是按 Agent 注册工具集合和只读边界交给 Spring AI Tool Calling。项目侧只负责过滤写入、通知、shell、memory 这类不适合自动调用的工具。

## 怎么防止危险工具

> 第一层按 evidence source 只选 docs/search MCP，第二层只允许 search、docs、fetch、read、get、open、list、resolve 语义的工具，第三层 `GuardedToolCallback` 在调用期再次检查授权集合和危险名称。create、update、write、send、notify、memory、shell 不会进入证据链路。

## 工具调用失败怎么办

> 参数错误、未授权和调用异常会被结构化归一化，并记录为失败 ToolInvocation。只有成功调用才会进入 evidence normalizer。工具失败时模型可以基于已有 evidence 回答或说明证据不足，但不能把错误文本当成检索结果。

## 为什么 PGVector 而不是更重的向量数据库

> 这是根据当前数据量、部署目标和运维成本做的选择。PGVector 已能满足项目知识语义检索，并和 PostgreSQL 一起完成轻量部署。只有进入更大规模、多租户隔离、高并发或专门索引治理场景时，才有充分理由引入独立向量数据库。这是技术选型方法，不是说 Milvus 本身不好。

## BM25、RRF、Small-to-Big 会不会过度设计

> 现在它们不是默认全开。项目冻结了 60 条评测数据，BM25/RRF 只有在精确术语子集 Hit@5 提升至少 10 个百分点或修复至少 3 个 case 时保留；Small-to-Big 也有关键点覆盖率和 Faithfulness 门槛。live evaluation 未完成前，我不会在简历中写确定的效果提升。

## 记忆机制是什么

> 这是 Session 短期记忆。主链路接入了 Spring AI Community `SessionMemoryAdvisor`，通过 `sessionId` 注入会话上下文。现阶段默认使用 InMemorySessionRepository 验证框架语义，原有 conversation 表继续作为跨重启兜底，后续再迁移到 JDBC Session 存储。

## 为什么用 spring-ai-session 而不是继续 ChatMemory

> Spring AI 2.0.0 GA 本体还没有稳定 Session API，但社区版 `spring-ai-session` 已经提供 Session、SessionEvent、SessionService、SessionMemoryAdvisor 和压缩触发器/策略，并且方向上更接近 Spring AI 2.1 对 ChatMemory 的替代思路。ChatMemory 只作为兜底，不再作为新的扩展主线。

## 摘要会不会把错误事实记住

> 摘要只提取用户明确表达的目标、约束、确认决策、未解决问题和回答偏好，不保存工具输出、外部事实或模型猜测。evidence trace 生命周期只在当前 Run，和 Session Memory 分离。

## 上下文过长怎么办

> 模型调用层由 `ContextBudgetAdvisor` 在 Advisor Chain 最前面做预算守卫。估算单位叫 context-units，是中英文 heuristic，不是精确 tokenizer。超过停止阈值时拒绝新模型调用，避免把超长上下文直接丢给模型。

## 并发更新记忆怎么办

> 当前 Spring AI Session 接入先使用内存仓储验证链路，跨重启仍由项目原有 conversation 表兜底。后续迁移 JDBC Session 时再处理乐观锁、TTL 和摘要游标，避免现在同时改运行链路和持久化模型。

## 项目当前最诚实的边界

- 已完成：Spring AI 2 主链路、Advisor Chain 基础设施、社区 Session 接入、MCP Guard 包装、旧 Harness 主路径清理、默认测试隔离。
- 已验收：默认单测、跳过测试打包、`git diff --check`。
- 待外部额度恢复后验收：完整 MCP evidence 回答、三组 RAG 对照和消融结论；这些结果完成前不写效果提升数字。
- 暂不做：多 Agent、长期向量记忆、精确 tokenizer、reranker、权限系统、前端控制台和新 Agent SDK。
