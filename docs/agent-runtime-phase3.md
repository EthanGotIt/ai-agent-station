# Agent Runtime Phase 3：Agentic RAG 执行链路

状态：核心代码已完成，目标单测已通过，真实向量库/ES smoke 待本地环境与 key 验证。

## 技术评估

本阶段不引入 OpenAI Agents SDK、LangGraph、CrewAI 等新框架。

原因：

- 当前 RAG 已具备 Query Rewrite、PGVector 语义召回、Elasticsearch BM25、RRF 融合、Small-to-Big 父块扩展和证据去重。
- Phase 3 的目标不是重写检索算法，而是把检索显式纳入 Agent Runtime：可规划、可解释、可追踪。
- 现有 Flow Plan、RagAnswerAdvisor、`rag_evidence` 事件已经能承载这次升级，换框架收益不足。

## 本阶段交付

- Flow Plan 支持 `RAG` 步骤类型，计划生成提示词会引导模型在需要知识库证据时使用 `type=RAG`。
- `RAG` 步骤不依赖 MCP 工具授权集合，不会被当成外部工具调用。
- 新增 `RagEvidenceAssembler`，把 Advisor 元数据整理成 Runtime evidence payload。
- `rag_evidence` payload 显式输出：
  - `pipeline`：Query Rewrite、Hybrid Recall、RRF、Small-to-Big、Deduplicate。
  - `queries`：本轮实际检索 query。
  - `evidenceCount` / `noEvidence` / `skippedReason`。
  - `evidences`：来源、召回 query、父块扩展、分数、内容预览。
- `RagAnswerAdvisor` 会跳过明显非 RAG 请求，避免所有普通生成请求都触发检索。

## 验收测试

已覆盖：

- `RAG` 类型步骤可通过计划校验，且不要求外部 MCP 工具授权。
- RAG evidence 输出包含 pipeline、query、证据数量和父块扩展信息。
- 无召回结果时仍输出 `noEvidence` 语义。
- 同一父块的证据会去重。
- 非 RAG 简单请求不会触发检索。
- Parent-Child 扩展、RRF 融合、证据去重继续由原有 RAG 支持测试覆盖。

推荐命令：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=RagAnswerAdvisorTest,RagEvidenceAssemblerTest,RagRetrievalSupportTest,FlowPlanSupportTest,FlowToolCapabilityServiceTest,AgentModelPortTest,AgentRunLifecycleVOTest,AgentRunAggregateTest,AgentContextWindowServiceTest" test
```

已执行结果：目标单测 42 个通过，0 failures，0 errors。

## Smoke 示例

输入：

```text
请仅基于已导入的 Markdown 知识回答 Spring AI MCP Client 常见的接入方式，不要调用外部 MCP 搜索工具。
```

预期：

- Flow Plan 中出现 `type=RAG` 的步骤。
- `tool_routing` 不注入外部 MCP 搜索工具。
- `rag_evidence.pipeline` 包含 Query Rewrite、Hybrid Recall、RRF、Small-to-Big 和 Deduplicate。
- `rag_evidence.evidences` 能看到 `hitChunkId`、`parentChunkId`、`parentExpanded`、`sourceType` 和 `contentPreview`。
- 如果没有召回结果，`rag_evidence.noEvidence=true`，最终回答应说明无法从知识库确认。

## 答辩材料

一句话亮点：

> 不是继续堆 RAG 算法，而是把 RAG 显式升级为 Agent Runtime 中可规划、可解释、可追踪的 `RAG` 步骤，让 Query Rewrite、混合召回、RRF、Small-to-Big 和证据去重都能在执行链路中复盘。

高频追问：

- Agentic RAG 和普通 RAG 有什么区别？
  普通 RAG 是模型调用前的隐式增强；这里把检索显式挂到 Flow Plan，作为可规划步骤，并把 query、证据、父块扩展和无召回状态输出到运行事件。
- 为什么不重写 RAG？
  现有检索链路已经覆盖主要质量优化点，本阶段重点是可控性和可解释性。
- 没有召回结果怎么办？
  `rag_evidence` 会输出 `noEvidence=true`，模型回答需要说明无法从知识库确认，而不是假装有证据。
