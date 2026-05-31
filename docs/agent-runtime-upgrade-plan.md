# AI Agent Station 渐进式 Agent Runtime 升级总控方案

## 当前进度

| 阶段 | 状态 | 代码 | 测试 | 文档 | 答辩材料 |
| --- | --- | --- | --- | --- | --- |
| Phase 1：统一 Run / Step 执行模型 | 已完成 | 已完成 | 已通过目标单测 | 已完成 | 已沉淀 |
| Phase 2：Tool Calling 治理 | 已完成 | 已完成 | 已通过目标单测 | 已完成 | 已沉淀 |
| Phase 3：RAG 显式 Agentic 化 | 已完成 | 已完成 | 已通过目标单测 | 已完成 | 已沉淀 |
| Phase 4：上下文治理与轻量记忆边界 | 已完成 | 已完成 | 已通过目标单测 | 已完成 | 已沉淀 |
| Phase 5：整体收口与最终验收 | 已完成 | 不新增功能 | 已通过完整测试 | 已完成 | 已沉淀 |
| Phase 6：MCP 执行时注入、Prompt 收敛与轻量记忆治理 | 已完成 | 已完成 | 已通过完整测试 | 已完成 | 已沉淀 |
| Phase 7：持久化 Session 短期记忆 | 已完成 | 已完成 | 已通过目标单测与真实双轮 API smoke | 已完成 | 已沉淀 |

## 项目变化

项目已经从单纯的 Spring AI 组件装配平台，推进为轻量受控 Agent Runtime：

- Phase 1 补齐 `Run -> Plan -> Step -> Result` 生命周期，使执行链路可追踪、可终止、可复盘。
- Phase 2 在 MCP 动态工具路由基础上加入 Tool Guard，使模型不能绕过本轮授权工具集合随意调用工具。
- Phase 3 将 RAG 显式纳入 Flow Plan，支持 `RAG` 步骤和结构化 `rag_evidence` 复盘。
- Phase 4 补齐上下文治理边界，明确项目规则、用户偏好、会话摘要的 session 级隔离。
- Phase 6 将 MCP 从计划阶段绑定具体工具名收敛为执行阶段注入 ToolCallback，删除 Flow Plan / 运行态里的 `toolName` 字段，并引入轻量 `ContextUnitEstimator` 抽象。
- Phase 7 补齐持久化 session 短期记忆，只记录用户输入和最终回答，不把内部 prompt 当成用户记忆；session 历史和当前 Run 步骤摘要分别治理。
- 当前项目仍不是完整多 Agent 通信框架、跨 session 长期记忆系统或危险工具沙箱，后续阶段只做和现有 Runtime 贴合的轻量增强。

## 技术评估

Phase 1 结论：不引入 OpenAI Agents SDK、LangGraph、CrewAI 等新框架。

原因是本阶段只统一运行态，现有 Spring AI、Flow Plan、`ai_agent_run`、`ai_agent_step_run` 已能承载，换框架会带来额外适配成本。

Phase 2 结论：继续沿用现有 Spring AI + MCP ToolCallback 链路，不引入新框架。

原因是工具风险分级、运行时工具筛选、禁用工具拦截、工具异常归一化都可以在现有链路完成：

- 路由层：`FlowToolCapabilityService` 筛选本轮候选工具，并输出 blocked 工具及原因。
- 注入层：`AgentModelPort` 注入工具前再次过滤不在本轮 allowed set 或被 Tool Guard 判定危险的工具。
- 调用层：`GuardedToolCallback` 统一工具参数错误、调用异常和越权调用返回。

后续每个 Phase 开始前仍需重新评估是否引入外部 Agent 框架。默认策略是先保留当前 Runtime，只有当 trace、resume、handoff、复杂状态图的自研成本明显超过接入成本时，再考虑小范围引入。

Phase 3 结论：继续沿用现有 Spring AI Advisor + Flow Plan Runtime，不引入新框架。

原因是当前 RAG 已经具备 Query Rewrite、PGVector 语义召回、Elasticsearch BM25、RRF、Small-to-Big 和证据去重，本阶段只需要把它显式化为 `RAG` 步骤和可追踪 `rag_evidence`，不需要重写检索链路。

Phase 4 结论：继续沿用现有 Spring AI ChatMemory + Flow Plan Runtime，不引入新框架。

原因是本阶段关注上下文隔离和轻量记忆边界，现有 `MessageChatMemoryAdvisor`、`AgentExecutionContextVO`、context guard 和运行态表已经能承载。OpenAI Agents SDK、LangGraph、CrewAI 的优势主要在复杂图执行、多 Agent handoff 和更完整 runtime，本阶段引入会增加适配成本。

Phase 5 结论：不引入新框架，不继续扩功能，只做最终收口和验收。

原因是 Phase 1-4 已完成轻量受控 Agent Runtime 的核心升级，继续加功能会扩大风险。当前更重要的是保证代码、测试、README、smoke、简历和答辩材料一致，避免写出未落地能力。

Phase 6 结论：继续沿用 Spring AI + MCP ToolCallback + Flow Plan Runtime，不引入新 Agent 框架，也不引入真实 tokenizer 库。

原因是当前目标是收敛工具注入时机、Prompt 职责和记忆边界。Spring AI 已提供运行时 ToolCallback 注入能力，现有 Tool Guard 和 Run/Step 运行态可承载本次调整；上下文预算只需要借鉴 `TokenCountEstimator` 的抽象方式，实现轻量 `ContextUnitEstimator` 和 heuristic 默认实现，不需要引入 jtokkit、HuggingFace tokenizer 或 DJL tokenizer。

Phase 7 结论：继续沿用 Spring AI + Flow Plan Runtime + MyBatis，不引入新 Agent 框架，也不接真实 tokenizer。

原因是本阶段缺口是持久化 session 短期记忆，不是复杂状态图或多 Agent handoff。新增 `ai_agent_conversation_message`、`AgentConversationMemoryService`、`SessionContextAssembler` 和 `AgentRuntimeAdvisorPolicy` 即可补齐同一 session 跨请求、跨重启恢复，并避免内部 prompt 污染 ChatMemory，不需要迁移现有执行模型。

## 代码冗余与后续清理

以下是当前可接受但需要跟踪的观察项，Phase 1/2 收尾阶段不扩大重构：

- `AiAgentController` 中 Run 详情 DTO 手工映射增多；如果后续继续扩展运行详情，再考虑抽出 mapper。
- `AgentRunLifecycleVO` 通过 stepId 字符串推导运行阶段；Phase 3 后如果内置节点继续增多，再沉淀 step 常量或阶段枚举。
- `Step4PlanExecuteNode` 的 RAG evidence 输出已拆到 `RagEvidenceAssembler`；后续如果 RAG 事件继续扩展，可再沉淀为独立 Runtime event service。
- `markPlannedStepTerminal` 当前用于未执行步骤落终态；Phase 4 如引入恢复/重试，需要升级为 create-or-update 防重复语义。
- `qwen3.7-max` 在手工 AI 测试中多处重复；如果后续模型频繁切换，再抽测试常量或配置 fixture。
- `ToolGuardPolicy` 当前按工具名启发式分级；如果后续接入更多工具，可演进为配置化风险策略。
- `AgentContextBoundaryService` 当前只做轻量偏好识别和 session 级边界，不做长期记忆冲突合并；如后续真的引入长期记忆，需要单独设计记忆写入、过期、覆盖和审计策略。
- `SessionContextAssembler` 和 `ContextWindowGuard` 已分别处理 session 历史与当前 Run step outputs；如果后续继续扩展上下文来源，可再抽统一 context section assembler，但当前不合并两层预算，避免职责混乱。
- `AgentConversationMemoryService` 当前只保存 USER 和 ASSISTANT 用户可见消息；长期记忆、偏好沉淀、消息过期清理和隐私审计仍是后续独立议题。
- `SessionContextAssembler` 当前固定加载最近 20 条消息，超预算时保留最近 4 条原文；真实流量验证后再将预算和保留数量配置化。
- `ai_agent_conversation_message` 当前没有归档和过期清理任务；如果进入长期运行环境，需要增加 session 消息保留周期。
- Phase 7 健康治理已删除重复的 `memoryConversationId`、恒定 `message_status` 和单列 `run_id` 索引；消息表使用 `(run_id, role)` 唯一约束防止重复写入。
- `ContextUnitEstimator` 当前是 heuristic 估算，不等价于 `qwen3.7-max` 的精确 tokenizer；后续如要实现精确预算，可单独接入 Qwen tokenizer 或 provider usage 校准。
- Phase 6 已将工具注入规则抽为 `AgentStepToolInjectionPolicy`，避免继续用反射测试 `Step4PlanExecuteNode` 私有方法；后续工具注入规则变化优先改 policy，不把判断散落到节点里。
- Flow Plan / `ai_agent_step_run` 已不再保存 `toolName`，但 MCP 配置中的 `toolNames` 仍是工具路由和授权注入的元数据，不属于冗余字段。
- `AgentPlanPromptFactory` 已注入上下文边界，提示词继续变长；后续如果继续扩 prompt，可抽出 prompt section builder，避免 prompt 工厂膨胀。
- `AgentRunDetailResponseDTO` 继续增加运行态字段，Controller 手工映射压力上升；后续可视情况抽 mapper，但当前不扩大重构。
- 当前暂不抽 mapper、不拆 prompt 工厂、不继续改运行态表结构，避免收口阶段引入非必要回归风险；以上观察项作为后续维护项保留。

## 阶段验收记录

Phase 1 已验收：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=AgentRunAggregateTest,AgentRunLifecycleVOTest,FlowPlanSupportTest,AgentContextWindowServiceTest" test
mvn -q "-DskipTests" package
git diff --check
```

结果：目标单测 18 个通过，跳过测试打包通过，`git diff --check` 通过。

Phase 2 已验收：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=FlowToolCapabilityServiceTest,FlowPlanSupportTest,AgentModelPortTest,AgentRunLifecycleVOTest,AgentRunAggregateTest,AgentContextWindowServiceTest" test
```

结果：目标单测 27 个通过。

跳过测试打包：`mvn -q "-DskipTests" package` 通过。

未完成验收：

- 未跑真实 DashScope/API smoke，`qwen3.7-max` 当前是配置层切换，供应商侧可用性待真实 key 验证。
- 未跑真实 MCP Server 调用，Tool Guard 已通过单测验证路由、计划校验和回调异常归一化。
- 未跑真实 PGVector/Elasticsearch RAG smoke，Agentic RAG 已通过单测验证计划类型、evidence 输出、无召回、父块扩展、证据去重和非 RAG 跳过。

Phase 3 已验收：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=RagAnswerAdvisorTest,RagEvidenceAssemblerTest,RagRetrievalSupportTest,FlowPlanSupportTest,FlowToolCapabilityServiceTest,AgentModelPortTest,AgentRunLifecycleVOTest,AgentRunAggregateTest,AgentContextWindowServiceTest" test
```

结果：目标单测 42 个通过。

跳过测试打包：`mvn -q "-DskipTests" package` 通过。

Phase 4 已验收：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=AgentContextBoundaryServiceTest,AgentContextWindowServiceTest,AgentModelPortTest,FlowPlanSupportTest" test
```

结果：目标单测 22 个通过，0 failures，0 errors。

扩展回归：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=AgentContextBoundaryServiceTest,RagAnswerAdvisorTest,RagEvidenceAssemblerTest,RagRetrievalSupportTest,FlowPlanSupportTest,FlowToolCapabilityServiceTest,AgentModelPortTest,AgentRunLifecycleVOTest,AgentRunAggregateTest,AgentContextWindowServiceTest" test
```

结果：扩展回归单测 47 个通过，0 failures，0 errors。

未完成验收：

- 未跑真实 DashScope/API smoke，`qwen3.7-max` 当前仍按配置层与单测验证处理，供应商侧可用性待真实 key 验证。
- Phase 4 当时未跑真实跨请求 API smoke，仅通过 session 作用域和 context boundary 单测验证隔离。
- 未实现复杂长期记忆，本阶段明确保持 `longTermMemoryEnabled=false`。

Phase 5 已验收：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q "-DskipTests=false" test
```

结果：Surefire 报告 36 个测试类，170 个测试通过，0 failures，0 errors，0 skipped。

```powershell
mvn -q "-DskipTests" package
```

结果：通过。

```powershell
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=AiClientModelDaoTest" test
```

结果：11 个测试通过，0 failures，0 errors；本地 MySQL `ai_client_model.model_id=2001` 已同步为 `qwen3.7-max`。

```powershell
rg -n "<旧模型关键字正则>" README.md docs ai-agent-station-app\src\test docs\dev-ops\mysql\sql\ai-agent-station.sql
git diff --check
```

结果：旧模型名无匹配；`git diff --check` 返回码 0，仅有 CRLF 提示。

真实 smoke 未执行：

- `OPENAI_API_KEY`、`JINA_API_KEY`、`CONTEXT7_API_KEY`、`EXA_API_KEY` 当前均未设置。
- `scripts/dev/up-local-stack.ps1`、`start-app-local.ps1`、`import-markdown-rag.ps1`、`run-local-smoke.ps1` 均存在，可在 key 就绪后执行。

Phase 6 已验收：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=FlowPlanSupportTest,FlowToolCapabilityServiceTest,AgentModelPortTest,AgentContextBoundaryServiceTest,AgentContextWindowServiceTest,AgentStepToolInjectionPolicyTest" test
```

结果：目标单测 34 个通过，0 failures，0 errors。

删除 Flow Plan `toolName` 后补充验收：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=FlowPlanSupportTest,FlowToolCapabilityServiceTest,AgentModelPortTest,AgentContextBoundaryServiceTest,AgentContextWindowServiceTest,AgentStepToolInjectionPolicyTest,AiAgentStepRunDaoTest,AgentRunLifecycleVOTest" test
```

结果：目标单测 39 个通过，0 failures，0 errors。

```powershell
mvn -q "-DskipTests=false" test
```

结果：完整测试 177 个通过，0 failures，0 errors，0 skipped。

```powershell
mvn -q "-DskipTests" package
git diff --check
```

结果：跳过测试打包通过；`git diff --check` 返回码 0，仅有 CRLF 提示。

未完成验收：

- 未跑真实 DashScope/API smoke，真实模型可用性仍依赖当前环境 key 和供应商侧 `qwen3.7-max` 可用性。
- 未跑真实 MCP Server 端到端工具调用 smoke；本阶段通过单测验证路由、注入判断和 GuardedToolCallback 异常归一化。
- SQL seed 和当前本地 DB system prompt 已同步 Phase 6 文案；`ai_agent_step_run.tool_name` 已从当前本地 DB 删除。其他环境如果不是从 seed 重建，需要补 migration 或手动数据更新。

Phase 7 已验收：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=SessionContextAssemblerTest,AgentConversationMemoryServiceTest,AgentContextBoundaryServiceTest,AgentContextWindowServiceTest,AiAgentConversationMessageDaoTest,AgentRuntimeAdvisorPolicyTest" test
```

结果：目标单测 17 个通过，0 failures，0 errors，0 skipped。

本地 MySQL 已按 Phase 7 最终结构同步：`ai_agent_conversation_message` 已创建，`ai_agent_run.session_context_summary` 保存执行开始前注入的 session 短期记忆快照。主 seed SQL 已包含最终表结构，不额外维护未接入 migration 框架的重复增量 SQL。Phase 7 健康治理进一步删除恒定 `message_status` 和重复单列 `run_id` 索引，并新增 `(run_id, role)` 唯一约束。

最终回归：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q clean "-DskipTests=false" test
mvn -q "-DskipTests" package
git diff --check
```

结果：完整测试 187 个通过，0 failures，0 errors，0 skipped；跳过测试打包通过；`git diff --check` 返回码 0，仅有 CRLF 提示。

真实 smoke：

- Docker `pgvector`、Elasticsearch 和 5 个 MCP Client 均完成初始化。
- Flow、工具路由、RAG evidence 链路已通过真实本地请求。
- 同一 `sessionId` 双轮请求已验证：第二轮流式 `context_boundary` 与 `GET /api/v1/agent/run/{runId}` 均返回执行前注入的 session 历史快照。
- 使用 `scripts/dev/start-app-local.ps1` 启用 PGVector 后，短 RAG 请求已验证 `vectorHits=6`、`bm25Hits=6`、`finalEvidence=6`。
- 修复 `scripts/dev/run-local-smoke.ps1` 可选 `context_guard` 检查对缺少 `subType` 事件的严格属性访问问题。

未完成项：

- 未验证远程 MCP 工具的真实业务调用结果；当前已验证 MCP 握手、工具路由与 ToolCallback 注入链路。
- 未增加消息归档和过期清理任务；当前仅实现持久化 session 短期记忆闭环。
