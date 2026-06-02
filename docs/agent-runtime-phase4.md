# Agent Runtime Phase 4：上下文治理与轻量记忆边界

> 历史阶段记录：当前执行内核已在 Phase 8 替换为 Spring AI Alibaba ReactAgent GraphRuntime。

状态：核心代码已完成，目标单测已通过，真实 API smoke 待本地 key 与服务环境验证。

## 技术评估

本阶段不引入 OpenAI Agents SDK、LangGraph、CrewAI 等新框架。

原因：

- Phase 4 关注的是 `sessionId`、项目规则、用户偏好和会话摘要的隔离边界，不是复杂多 Agent 图执行。Phase 7 已将 Flow Runtime 记忆收敛为持久化 session 短期记忆。
- 现有 Spring AI `MessageChatMemoryAdvisor` 可承载内存窗口记忆，Flow Plan Runtime 已有 `AgentExecutionContextVO` 和 context guard。
- 引入新框架会扩大模型调用、工具调用、运行态持久化和测试适配面，收益主要在复杂状态图和 handoff，不是本阶段核心。

## 本阶段交付

- 新增 `AgentContextBoundaryVO`，显式描述单次运行的上下文边界：
  - `sessionId`
  - `projectRuleScope`
  - `userPreferenceScope`
  - `conversationScope`
  - `projectRules`
  - `userPreferences`
  - `sessionContextSummary`
  - `longTermMemoryEnabled`
- 新增 `AgentContextBoundaryService`：
  - 统一 session 作用域解析。
  - 识别当前请求里的轻量用户偏好，但不做跨 session 长期记忆。
  - 生成可注入提示词的上下文治理边界。
  - 生成运行流 `context_boundary` payload。
- `FlowPlanExecuteService` 在 Run 初始化时绑定上下文边界。
- `RootNode` 输出 `context_boundary` 事件，便于 smoke 和运行复盘。
- `AgentPlanPromptFactory` 在计划、步骤执行、监督、总结提示词中注入上下文治理边界。
- Phase 4 当时由 `AgentModelPort` 使用统一 conversationId 写入 Spring AI ChatMemory advisor 参数；Phase 7 已删除 Flow Runtime 的该透传逻辑。
- `GET /api/v1/agent/run/{runId}` 返回 `contextBoundary`，用于查看本轮上下文隔离策略。
- 修复 context guard 的续执行问题：历史首次压缩后，后续步骤继续使用 `history_summary + 最近步骤输出`，避免摘要被清空后把完整历史重新塞回提示词。

## 边界说明

本阶段不是完整长期记忆系统：

- 项目规则：项目级固定规则，所有 session 共享，但只表达运行策略，不保存用户私人偏好。
- 用户偏好：仅从当前请求中轻量识别，并按 `session:{sessionId}:preferences` 标记作用域，不跨 session 复用。
- 会话上下文：Phase 4 当时由 Spring AI ChatMemory 使用 `sessionId` 隔离内存窗口；Phase 7 已改为通过 `ai_agent_conversation_message` 恢复同一 session 的持久化短期上下文。
- 压缩摘要：只代表当前 run 已执行步骤的摘要，不代表全局长期记忆。
- 长期记忆：`longTermMemoryEnabled=false`，不把偏好写入 MCP memory 或外部持久化记忆。

## 验收测试

已覆盖：

- 不同 session 的用户偏好作用域、会话上下文作用域彼此隔离。
- 当前请求没有偏好标记时，不会继承上一轮偏好。
- 压缩摘要可绑定到 context boundary，并进入提示词上下文。
- 上下文过长触发压缩。
- 压缩后继续执行时，仍使用 `history_summary` 和最近步骤输出，不重新展开完整历史。
- Tool Guard、Flow Plan、Agentic RAG 既有目标测试继续通过。

推荐命令：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=AgentContextBoundaryServiceTest,AgentContextWindowServiceTest,AgentModelPortTest,FlowPlanSupportTest" test
```

已执行结果：目标单测 22 个通过，0 failures，0 errors。

扩展回归命令：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=AgentContextBoundaryServiceTest,RagAnswerAdvisorTest,RagEvidenceAssemblerTest,RagRetrievalSupportTest,FlowPlanSupportTest,FlowToolCapabilityServiceTest,AgentModelPortTest,AgentRunLifecycleVOTest,AgentRunAggregateTest,AgentContextWindowServiceTest" test
```

已执行结果：扩展回归单测 47 个通过，0 failures，0 errors。

## Smoke 示例

输入 A：

```text
以后请用中文简洁回答。请把当前项目的 Agent Runtime 主链路总结成 3 点。
```

输入 B：

```text
请用英文详细解释当前项目的 Agent Runtime 主链路。
```

预期：

- 两次请求使用不同 `sessionId` 时，`context_boundary.sessionId` 和 `context_boundary.conversationScope` 不同。
- `context_boundary.userPreferenceScope` 按 session 区分。
- `context_boundary.userPreferences` 只来自当前请求，不从其他 session 继承。
- `GET /api/v1/agent/run/{runId}` 能看到 `contextBoundary`。
- 如果上下文达到阈值，流式事件中出现 `context_guard`，后续提示词继续使用压缩摘要。

## 答辩材料

一句话亮点：

> Phase 4 在 Spring AI ChatMemory 和 context guard 基础上补齐 session 隔离边界；Phase 7 已进一步将 Flow Runtime 收敛为持久化 session 短期记忆和当前 Run 摘要分层治理。

> 后续状态：Phase 7 已新增持久化 session 短期记忆，跨重启恢复同一 session 的用户输入和最终回答。本文保留 Phase 4 当时的阶段边界。

高频追问：

- 上下文过长怎么办？
  运行时用 context guard 做预算估算，达到压缩阈值后把历史步骤折叠成 `history_summary`，并只保留最近步骤输出；达到停止阈值后跳过新的模型调用，转本地总结或跳过后续步骤。
- 项目规则和用户偏好怎么隔离？
  项目规则是固定运行策略，所有 session 共享；用户偏好只从当前请求轻量识别，并标记为 `session:{sessionId}:preferences`，不写入长期记忆，不跨 session 复用。
- 为什么不直接无限塞历史？
  历史越长，成本、延迟和幻觉风险越高；压缩摘要牺牲部分细节，换来可控上下文预算和可复盘执行链路。
- 为什么不做复杂长期记忆？
  当前项目定位是轻量受控 Agent Runtime。长期记忆需要隐私、过期、冲突合并和检索治理，本阶段只把边界讲清楚并做 session 级隔离，避免把简历项目做成不可控大重构。
